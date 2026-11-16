# Protocole BLE du boîtier E85 Biomotors (Bioflex)

Reconstitué par rétro-ingénierie de l'appli officielle Android **2.4.2** (Flutter,
Dart 3.10.8, code décompilé avec [blutter](https://github.com/worawit/blutter)).
Usage **éducatif / interopérabilité**, pour son propre boîtier.

## Transport
Liaison « série » sur BLE (GATT). L'appli reconnaît **3 familles de modules** et
choisit le couple de caractéristiques correspondant :

| Module | Service | Écriture (TX) | Notification (RX) |
|---|---|---|---|
| FFF0 (HM-10 / clone) | `0000fff0-0000-1000-8000-00805f9b34fb` | `0000fff2-…` | `0000fff1-…` |
| Nordic UART (NUS) | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | `6e400002-…` | `6e400003-…` |
| Microchip ISSC (BM71) | `49535343-fe7d-4ae5-8fa9-9fafd205e455` | `49535343-8841-43f4-a8d4-ecbe34729bb3` | `49535343-1e4d-4bd9-ba61-23c647249616` |

> **Confirmé sur boîtier réel** (nom BLE `BM71_BLE`, 04/11/2026) : module Microchip BM71.
> Le service `fe7d` expose `1e4d` (props `0x1c` : notify + write) et `8841` (props `0xc` : write).
> Le module a aussi deux services propriétaires Microchip (`49535343-5d82-…` / `49535343-c9d0-…`)
> qui ne sont **pas** la liaison série : ne pas s'y abonner (bug de l'appli v0.2).

Séquence de connexion :
1. `connectGatt` puis **requestMtu(155)**.
2. `discoverServices`.
3. S'abonner à la caractéristique **notify** (écrire `01 00` sur le descripteur CCCD `0x2902`).
4. Écrire les commandes sur la caractéristique **write**.

## Encodage des commandes
Texte **ASCII**, terminé par `\r\n` (`0D 0A`), encodé octet par octet (`AsciiEncoder`),
puis `write()` sur la caractéristique d'écriture.

## Lire les données (dont le % E85)
- Commande de lecture : la lettre **`L`** → en octets `4C 0D 0A`.
- Le boîtier répond par une **trame ASCII** composée de tokens `<LETTRE><valeur>` (la
  lettre marqueur, A–Z, vient **avant** sa valeur, éventuellement signée), confirmé sur
  boîtier réel : `SM1F040C030O072T+019S+012` (voir [COMMANDES.md](COMMANDES.md)). La réponse peut
  arriver en plusieurs notifications ; l'appli considère la trame **complète 500 ms
  après le dernier morceau reçu** (pas de terminateur explicite fiable).
- Décodeur côté appli : `valueFromData(trame, lettre)` (recherche du marqueur +
  `substring` + `tryParse`), appelé depuis `setTramValue()`.

### Champ éthanol
**% E85 = valeur au marqueur `O`** (entier).
Vérifié des deux côtés : le widget d'affichage lit ce champ, et c'est lui qui
détermine l'étiquette carburant **SP / MIXTE / E85**.

### Autres champs de la trame
Sur un boîtier STD, la trame compte **6 champs** : `M F C O T S`. Le décodeur de l'appli
sait en gérer une vingtaine d'autres (champs d'autres modèles ou d'autres commandes). Le
détail — correspondance lettre → champ, statut confirmé / probable — est dans
[COMMANDES.md](COMMANDES.md).

## Écrire des réglages
Même canal, dans l'autre sens. `writeCommand()` envoie `<Lettre><valeur>`, dans un format
propre à chaque champ :
- entier brut pour le seuil et la consigne (`S15`, `M1`),
- **3 chiffres** zéro-paddés pour les enrichissements (`F045`, `C030`) et certains réglages
  expert (`H000`, `I000`),
- **base-36** puis `padLeft(4, '0')` (`toRadixString(36)`) pour quelques champs d'en-tête.

Le boîtier renvoie chaque commande en écho. Formats confirmés par capture des échanges de
l'appli officielle : voir [COMMANDES.md](COMMANDES.md).

Dans l'appli officielle, l'accès aux réglages est réservé au compte installateur
(et l'écran « Expert » à un code fourni par Biomotors). Ces contrôles relèvent de l'appli :
le boîtier, lui, accepte les commandes d'écriture standard sans authentification (voir
ci-dessous). Aucun identifiant ni mécanisme d'accès de l'appli n'est reproduit ici.

> ⚠️ Écrire de mauvaises valeurs d'injection peut faire mal tourner, voire
> endommager le moteur. Ne toucher aux réglages qu'après avoir compris chaque
> paramètre, et de préférence après une capture de référence.

## Vérification sur matériel réel (faite le 04/11/2026, boîtier STD)
Confirmé sur un boîtier réel `BM71_BLE` (STD, injection indirecte) :
- liaison série BM71 (`fe7d` / write `8841` / notify `1e4d`) ;
- commande `L` → trame `<LETTRE><valeur>` à 6 champs `M F C O T S` ;
- écriture `S18` acceptée sans authentification (écho puis `S+018` dans la trame suivante).

Le détail des champs est dans [COMMANDES.md](COMMANDES.md). Les boîtiers **GDI** (injection
directe) n'ont pas été testés.

Pour aller plus loin (champs des autres modèles, autres commandes) :
- log **HCI snoop** d'Android pendant l'usage de l'appli officielle, puis analyse dans
  Wireshark ;
- l'appli de ce dépôt journalise chaque notification (hex + ASCII), la trame assemblée et
  le décodage.
