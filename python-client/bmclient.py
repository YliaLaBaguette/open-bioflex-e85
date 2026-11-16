#!/usr/bin/env python3
"""
Client de test pour boîtier E85 Biomotors (Bioflex) — lecture BLE du % d'éthanol.

Reconstitué par rétro-ingénierie de l'appli officielle 2.4.2 (Flutter), à des
fins éducatives et pour ton propre boîtier.

Protocole :
  - Liaison "série" en BLE. 3 familles de modules possibles (voir MODULES).
  - On s'abonne à la caractéristique de notification, on écrit sur l'autre.
  - MTU demandé par l'appli : 155.
  - Commande de lecture : la lettre 'L' (ASCII) suivie de '\r\n'.
  - Le boîtier répond par une trame ASCII où chaque champ est de la forme
    <LETTRE_MARQUEUR><valeur> (la lettre précède sa valeur), les morceaux pouvant arriver en plusieurs
    notifications. L'appli considère la trame complète 500 ms après le dernier
    morceau reçu.
  - % d'éthanol = valeur au marqueur 'O'.

Dépendance : bleak  ->  pip install bleak
Usage :
    python bmclient.py --scan                 # lister les périphériques BLE
    python bmclient.py --address AA:BB:..      # se connecter par adresse
    python bmclient.py --name BM71_BLE         # se connecter par nom (sous-chaîne)
    python bmclient.py --name BM71_BLE --watch # lecture en boucle
"""

import argparse
import asyncio
import re
import sys

from bleak import BleakClient, BleakScanner

# --- Les 3 familles de modules BLE-UART reconnues par l'appli -----------------
# (service, caractéristique d'écriture, caractéristique de notification)
MODULES = [
    # FFF0 : HM-10 / clones chinois
    ("0000fff0-0000-1000-8000-00805f9b34fb",
     "0000fff2-0000-1000-8000-00805f9b34fb",
     "0000fff1-0000-1000-8000-00805f9b34fb"),
    # Nordic UART Service
    ("6e400001-b5a3-f393-e0a9-e50e24dcca9e",
     "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
     "6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
    # Microchip ISSC (BM71 / RN4870…)
    ("49535343-fe7d-4ae5-8fa9-9fafd205e455",
     "49535343-8841-43f4-a8d4-ecbe34729bb3",   # write (RX du module)
     "49535343-1e4d-4bd9-ba61-23c647249616"),  # notify (TX du module)
]

READ_CMD = b"L\r\n"          # commande de lecture
FRAME_IDLE = 0.5             # s : fin de trame = 500 ms sans nouveau morceau


async def do_scan():
    print("Scan BLE (5 s)…")
    devs = await BleakScanner.discover(timeout=5.0)
    if not devs:
        print("Aucun périphérique trouvé.")
        return
    for d in sorted(devs, key=lambda x: (x.name or "zz")):
        print(f"  {d.address}   {d.name or '(sans nom)'}")


async def pick_device(name=None, address=None):
    if address:
        return address
    print(f"Recherche d'un périphérique « {name} »…")
    dev = await BleakScanner.find_device_by_filter(
        lambda d, ad: bool(d.name) and name.lower() in d.name.lower(),
        timeout=15.0,
    )
    if not dev:
        print("Périphérique introuvable. Essaie --scan pour voir les noms.")
        sys.exit(1)
    print(f"Trouvé : {dev.address}  {dev.name}")
    return dev.address


def find_chars(client):
    """Choisit le couple (write, notify) parmi les 3 familles, selon ce que
    le périphérique expose réellement."""
    have = {c.uuid.lower() for s in client.services for c in s.characteristics}
    for service, wr, nt in MODULES:
        if wr.lower() in have and nt.lower() in have:
            return wr, nt
    # repli : première caractéristique accessible en écriture / notification
    wr = nt = None
    for s in client.services:
        for c in s.characteristics:
            props = set(c.properties)
            if wr is None and ({"write", "write-without-response"} & props):
                wr = c.uuid
            if nt is None and ({"notify", "indicate"} & props):
                nt = c.uuid
    return wr, nt


def parse_frame(text):
    """Trame = suite de tokens <LETTRE><valeur> (la lettre précède sa valeur,
    éventuellement signée), ex. SM1F040C030O072T+019S+012. Renvoie un dict
    LETTRE->valeur. Le % d'éthanol est au marqueur 'O'."""
    fields = {}
    for letter, value in re.findall(r"([A-Z])([+-]?\d+(?:[.,]\d+)?)", text):
        fields[letter] = value.replace(",", ".")
    return fields


async def read_once(client, wr, nt):
    buf = bytearray()
    loop = asyncio.get_running_loop()
    done = loop.create_future()
    timer = {"h": None}

    def schedule_finish():
        if timer["h"]:
            timer["h"].cancel()
        timer["h"] = loop.call_later(
            FRAME_IDLE, lambda: (not done.done()) and done.set_result(bytes(buf))
        )

    def on_notify(_handle, data: bytearray):
        buf.extend(data)
        schedule_finish()

    await client.start_notify(nt, on_notify)
    # write-without-response si dispo, sinon write classique
    try:
        await client.write_gatt_char(wr, READ_CMD, response=False)
    except Exception:
        await client.write_gatt_char(wr, READ_CMD, response=True)

    try:
        raw = await asyncio.wait_for(done, timeout=8.0)
    except asyncio.TimeoutError:
        raw = bytes(buf)
    finally:
        await client.stop_notify(nt)
    return raw


async def run(address, watch):
    print(f"Connexion à {address}…")
    async with BleakClient(address) as client:
        try:
            await client.request_mtu(155)   # ignoré par certains back-ends
        except Exception:
            pass
        wr, nt = find_chars(client)
        if not wr or not nt:
            print("Caractéristiques write/notify introuvables.")
            return
        print(f"write = {wr}\nnotify = {nt}\n")

        while True:
            raw = await read_once(client, wr, nt)
            text = raw.decode("ascii", errors="replace").strip()
            print("── trame brute :", repr(text))
            fields = parse_frame(text)
            if fields:
                print("   champs :", fields)
            e85 = fields.get("O")
            if e85 is not None:
                print(f"   >>> E85 ≈ {e85} %")
            else:
                print("   (marqueur 'O' absent — vérifie la trame ci-dessus)")
            if not watch:
                break
            await asyncio.sleep(1.0)


def main():
    ap = argparse.ArgumentParser(description="Client de test boîtier E85 Biomotors")
    ap.add_argument("--scan", action="store_true", help="lister les périphériques BLE")
    ap.add_argument("--address", help="adresse MAC / UUID du boîtier")
    ap.add_argument("--name", help="nom (sous-chaîne) du boîtier, ex. BM71_BLE")
    ap.add_argument("--watch", action="store_true", help="lecture en boucle")
    args = ap.parse_args()

    if args.scan:
        asyncio.run(do_scan())
        return
    if not args.address and not args.name:
        ap.error("donne --scan, --address ou --name")
    address = asyncio.run(pick_device(args.name, args.address))
    asyncio.run(run(address, args.watch))


if __name__ == "__main__":
    main()
