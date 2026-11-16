# Client de test — boîtier E85 Biomotors (Bioflex)

Lecture BLE du % d'éthanol, reconstitué par rétro-ingénierie de l'appli officielle 2.4.2.
Usage éducatif, pour ton propre boîtier.

## Installation
```
pip install -r requirements.txt
```

## Utilisation
```
python bmclient.py --scan                 # 1) trouver le nom/adresse du boîtier
python bmclient.py --name BM71_BLE         # 2) se connecter par nom (boîtier STD testé)
python bmclient.py --address AA:BB:CC:...  #    ou par adresse
python bmclient.py --name BM71_BLE --watch # lecture en boucle
```

Branche l'allumage (le boîtier doit être alimenté), ferme l'appli officielle
(un seul maître BLE à la fois), puis lance le scan.

## Ce que fait le client
1. Se connecte, demande un MTU de 155.
2. Repère le couple write/notify parmi les 3 familles de modules (FFF0 / Nordic UART / ISSC).
3. S'abonne aux notifications, envoie la commande `L\r\n`.
4. Assemble la trame (fin = 500 ms sans nouveau morceau), puis l'affiche brute
   **et** décodée en champs `<LETTRE><valeur>`.
5. Affiche le champ `O` = % E85.

## Si le marqueur `O` ne tombe pas juste
La trame brute est toujours affichée : repérer la valeur qui correspond au vrai
% d'éthanol affiché par l'appli officielle, et noter la lettre qui la porte. Idéalement,
comparer avec une capture HCI snoop de l'appli officielle.

## Réglages (écriture)
Ce client se limite à la lecture. L'écriture passe par le même canal, au format
`<Lettre><valeur>` propre à chaque champ (voir [COMMANDES.md](../docs/COMMANDES.md)) ;
l'application Android la prend en charge avec ses garde-fous.
