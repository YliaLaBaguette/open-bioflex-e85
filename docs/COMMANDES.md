# Commandes BLE du boîtier Bioflex

Les commandes passent sur la caractéristique d'écriture (voir [PROTOCOL.md](PROTOCOL.md)) :
du texte ASCII `<Lettre>[valeur]` terminé par `\r\n`. Le boîtier répond sur la
caractéristique de notification.

Le **format** est confirmé sur boîtier réel (BM71, STD, 04/11/2026) ; certaines
**correspondances lettre → paramètre** restent marquées *probable*.

## Lire l'état : commande `L`

`L` (`4C 0D 0A`) demande la trame d'état. La réponse est en `<LETTRE><valeur>` : la lettre
vient **avant** sa valeur (parfois signée). Exemple réel, contact mis, moteur arrêté :

```
SM1F040C030O072T+019S+012
```

| Champ | Ex. | Sens | Statut |
|---|---|---|---|
| `S`… (en tête) | — | marqueur de début de trame (sans valeur) | — |
| `O` | 072 | **% éthanol** | ✅ appli officielle 71–72 % |
| `T` | +019 | **température °C** | ✅ appli officielle 85 °C = `T+085` |
| `S` | +012 | **seuil de départ à froid °C** | ✅ écriture `S18` suivie par l'appli |
| `F` | 040 | **enrichissement à froid %** | ✅ modèle + formulaire « % Mode à froid » |
| `C` | 030 | **enrichissement à chaud %** (suit l'éthanol) | ✅ modèle + « % Mode à chaud » |
| `M` | 1 | **consigne de démarrage** (1 ou 2) | ✅ appli affiche consigne 2 quand la trame donne `M2` |

> La valeur appartient à la lettre qui la **précède** (prouvé par les signes `T+019` /
> `S+012`). Le `S` de tête, sans chiffre, marque le début. Décodage : `([A-Z])([+-]?\d+)`.

**`M` n'est pas le mode froid/chaud.** Le mode actif, lui, se déduit de `T` vs seuil `S`.
`M` est un réglage et ne suit donc pas la température (log du 06/11 : `M2` de +5 à +41 °C,
seuil franchi sans bascule).

## Écriture : confirmée, sans authentification

`S18` → le boîtier renvoie l'écho `S18`, puis la trame suivante contient `S+018`. Le boîtier
accepte donc l'écriture **sans authentification** : les restrictions d'accès (compte
installateur, code « Expert ») sont des règles de l'appli officielle, pas du boîtier.

- **Format d'écriture par champ, confirmé par capture HCI de l'appli officielle** (chaque
  commande est renvoyée en écho par le boîtier) :

  | Champ | Commande | Format |
  |---|---|---|
  | Seuil `S` | `S15` | entier **brut** (ni zéro de tête ni signe) |
  | Consigne `M` | `M1` / `M2` | entier **brut** |
  | Enrich. froid `F` | `F045` / `F050` | **3 chiffres zéro-paddés** |
  | Enrich. chaud `C` | `C025` / `C030` | **3 chiffres zéro-paddés** |

  La largeur acceptée **dépend du champ** (`S`/`M` bruts, `F`/`C` sur 3 chiffres). Les
  enrichissements `F` et `C` ne sont acceptés que **par paliers de 5** (`F035`, `F040`,
  `C025`, `C030`…), constaté sur boîtier réel. L'appli
  officielle envoie aussi une commande **`P`** après chaque lecture (un *poll* ; le boîtier
  n'y répond pas) — non nécessaire pour écrire un réglage.
- `Z` = **réinitialisation** des réglages (identifié dans le code).
- ⚠️ **Toute écriture BLE désactive le bouton poussoir et le potentiomètre du boîtier**
  (dixit le livret) : tout se règle ensuite par l'appli. Les réactiver demande une procédure
  Biomotors non publiée.

## Les 4 réglages d'un boîtier STD

Un boîtier STD (injection indirecte) n'expose que ces 4 réglages — exactement les 4 champs
réglables de la trame.

| Réglage | Lettre | Rôle | Valeurs (doc Biomotors) | Réglage physique | Écriture BLE |
|---|---|---|---|---|---|
| Seuil de départ à froid | `S` | température sous laquelle le mode froid s'applique | 12–15 °C (consigne 1), 30 °C (consigne 2) | — (appli) | ✅ `S15` (brut) |
| Enrich. à froid | `F` | surplus de carburant sous le seuil | 0–70 % par pas de 5 %, 35 % d'usine | potentiomètre, milieu = 35 % | ✅ `F045` (3 chiffres) |
| Enrich. à chaud | `C` | surplus au-dessus du seuil, [calculé selon l'éthanol](#enrichissement-à-chaud-une-courbe) | le réglage décale la courbe, par pas de 5 % | bouton, appui long ≥ 5 s puis +5 %/appui | ✅ `C030` (3 chiffres) |
| Consigne de démarrage | `M` | stratégie de départ à froid (voir plus bas) | 1 (usine) / 2 | bouton, 1 appui = 1, 2 appuis = 2 | ✅ `M1` / `M2` (brut) |

Lecture seule : `O` (% éthanol), `T` (température).

**Quand régler quoi.** Seuil, enrich. à froid et consigne → **moteur coupé, contact mis**.
Enrich. à chaud → **moteur tournant, OBD branché**, pour suivre les corrections de mélange.
Ajustement fin à l'OBD : additionner les corrections court + long terme, puis corriger `C`
du même ordre par pas de 5 % (Renault / Dacia / Nissan : valeur lue × 0,4). Voir
[PROCEDURE-INSTALLATION.md](PROCEDURE-INSTALLATION.md).

## Consigne de démarrage (`M` = 1 ou 2)

Le mode froid ajoute du carburant au démarrage (l'E85 s'enflamme mal sous ~12 °C, contre
~−40 °C pour le SP). Il s'active seul via la sonde de température fixée sur la culasse ; sur
le boîtier, la **LED rouge reste fixe** tant qu'il est actif.

**Relation seuil ↔ consigne.** Le seuil `S` dit *quand* le mode froid s'applique, la consigne
`M` dit *ce qu'il fait*. Chacun s'écrit par sa propre commande : la capture HCI de l'appli
officielle montre `S15` et `M1` / `M2` envoyés séparément, et le boîtier tient **consigne 1
avec un seuil personnalisé** (ex. 16 °C). Une interaction a été relevée : écrire la consigne
seule a **ramené le seuil à 12 °C** (log réel : `M1` → trame `S+012`). Après un changement de
consigne, relire le seuil et le réécrire si besoin.

| | Consigne 1 (`M1`, usine) | Consigne 2 (`M2`, dernier recours) |
|---|---|---|
| Seuil conseillé (livret) | 12–15 °C | 30 °C |
| Enrichissement | celui du réglage froid (`F`) | **+~80 % sur les 150 premières injections**, en plus |
| Pré-pulvérisation | non | **oui**, dès la mise du contact |
| Alimentation | indifférente | **+12 V après contact (APC)**, pas un + permanent |

- **Passer en consigne 2** si le départ à froid reste difficile en consigne 1 malgré toute
  la plage de réglage. Recommandée d'office pour certains moteurs (Fiat/Jeep petite cylindrée
  Euro 6 ; PSA 1.4/1.6 injection indirecte d'avant 2010).
- **À ne pas généraliser** : un excès d'enrichissement à froid laisse des imbrûlés et dilue
  l'huile.
- **Changement manuel** (bouton, moteur coupé, contact mis) : appui long jusqu'à LED bleue
  fixe, relâcher 1 s, appui long jusqu'à LED éteinte, relâcher 1 s, puis **1 appui = consigne
  1, 2 appuis = consigne 2** ; attendre le reclignotement, couper le 12 V pour valider.

## Enrichissement à chaud (une courbe)

Le boîtier **calcule lui-même** l'enrichissement à chaud selon le taux d'éthanol mesuré
(`O`), d'après le livret installateur :

| % éthanol | < 20 | 65 | 70 | 80 | 85 | 100 |
|---|---|---|---|---|---|---|
| Enrich. à chaud | 0 %\* | 28 % | 30 % | 35 % | 37 % | ~44 % |

\* sous ~20 % d'éthanol, le boîtier est inactif (LED bleue fixe) : il ne voit que de l'essence.

Le réglage « Mode à chaud » de l'appli **décale cette courbe** par paliers de 5 %. Le relevé
réel `O072` → `C030` tombe sur la courbe : `C` est donc la valeur appliquée pour l'éthanol
courant. **Reste à vérifier** si écrire `C` fixe une valeur ou décale la courbe.
L'enrichissement à froid (`F`), lui, est une valeur réglée (35 % d'usine, plage 0–70 %).

## Les autres lettres du décodeur

La trame STD ne porte que 6 champs (`M F C O T S`), mais le décodeur de l'appli gère une
vingtaine de lettres, pour les **25 champs du modèle complet** — présents sur d'autres
boîtiers ou via d'autres commandes.

Modèle, dans l'ordre du `toString` : `systemRegistration, type, processorVersion, lockId,
systemVersion, temperature, hotMode, coldMode, ethanol, mode, consStart, cylinder, coldCons,
pwmHigh, pwmLow, activeWays, currentHold, maxSpeedAction, pulverisationTime,
firstCycleEnrichment, cycleAfterStart, delta, ecoMode, isLock, TestMode`.

Alignement ancré sur les 3 champs confirmés en live (`T`, `O`, `M`) et l'ordre du modèle.
**Les 6 de la trame sont sûrs ; le reste est probable** (des trous dans les offsets empêchent
de garantir chaque ligne).

| Lettre(s) | Champ | Confiance |
|---|---|---|
| `T` `C` `F` `O` | temperature, hotMode, coldMode, ethanol | ✅ confirmé |
| `M` | mode → consigne de démarrage | ✅ confirmé |
| `S` | seuil de départ à froid | ✅ confirmé |
| `V` | consStart | 🟡 probable |
| `A` | cylinder | 🟡 probable |
| `H` | coldCons | 🟡 probable |
| `I` `Y` | pwmHigh, pwmLow | 🟡 probable |
| `X` | activeWays | 🟡 probable |
| `N` | currentHold | 🟡 probable |
| `R` `D` `K` | pulverisationTime, firstCycleEnrichment, cycleAfterStart | 🟡 probable |
| `J` `U` `W` | champs d'en-tête (registration / type / version) | 🟡 probable |

> Les trois effets de la consigne 2 (pré-pulvérisation, sur-enrichissement des premiers
> cycles) correspondent aux champs `consStart`, `pulverisationTime`, `firstCycleEnrichment`,
> `cycleAfterStart` : les lettres `V R D K` gagnent en vraisemblance.

Pour figer ces lettres, il faut un autre boîtier (GDI) qui les expose, ou une capture HCI de
l'appli officielle.

## Boîtier GDI (non pris en charge)

Tous les essais ont porté sur un boîtier **STD**. Un boîtier **GDI (injection directe)**
expose des réglages supplémentaires, donc d'autres commandes, **non gérées par l'application**
et non testées :

| Réglage GDI | Rôle | Lettre |
|---|---|---|
| MODE 1 à 9 / Auto | paramétrage GDI ; « Auto » lance l'[auto-apprentissage](#auto-apprentissage-gdi) | non identifiée |
| Nb de cylindres actifs | affiché par le système | `A` probable |
| DÉTECTION seuil 1 / 2 / 3 | seuil de déclenchement de l'injection induite | non identifiée |
| Mode économie d'énergie | veille après 10 min moteur arrêté (soft ≥ V7) | `ecoMode`, non identifiée |
| Réglages expert | PWM, maintien de courant… (code fourni par Biomotors) | `H I Y X N…` probables |

### Auto-apprentissage (GDI)

Réservé aux boîtiers GDI, lancé en choisissant **« Auto »** dans la liste MODE (absente sur
STD). Durée ~60 s (fiche FI23 de 2023 : « en roulage » ; appli 2.4.2 : « au ralenti avec des
coups de gaz » — la consigne a varié). À la fin, l'appli affiche **« Auto-apprentissage OK »** ;
un appui long l'annule.

Dans le code, l'écran d'apprentissage (`IntelligentLearningProcess`) n'envoie rien au
boîtier : c'est un minuteur + un affichage. C'est l'**écriture du mode « Auto »** (faite
avant cet écran) qui lance l'apprentissage ; sa lettre n'est pas identifiée. Sur STD, pas
d'auto-apprentissage : l'enrichissement à chaud suit la courbe ci-dessus, et l'ajustement fin
se fait à l'OBD.

## Annexe : gabarits et encodages d'écriture

Gabarits littéraux trouvés dans le binaire. La valeur est un nombre zéro-paddé.

| Gabarit | Forme | Encodage / usage |
|---|---|---|
| `Hxxx`, `Ixxx` | lettre + 3 chiffres | décimal (réglage) |
| `Jxxxxxxxx` | lettre + 8 chiffres | décimal (identifiant) |
| `Wxxxx` | lettre + 4 caractères | base-36 `toRadixString(36).padLeft(4,'0')` |
| `Q0xx` | `Q031 Q032 … Q255 Q256` | code type de microcontrôleur (DSPIC33 / PIC18) |
| `Z` | lettre seule | action (reset) |
| `S`, `M`, `P` | lettre + valeur | décimal |

Le boîtier utilise la **même lettre pour lire et écrire** un champ.

## Compléter le mapping

Méthode qui a servi à confirmer les formats d'écriture de `S`, `M`, `F` et `C` :

1. Activer le log **HCI snoop** d'Android (options développeur, ou
   `adb shell settings put global bluetooth_hci_log 1` puis redémarrer le Bluetooth).
2. Dans l'appli officielle, bouger **un seul** réglage à la fois.
3. Récupérer le fichier `btsnoop` (`adb bugreport`) et relever la commande émise
   `<Lettre><valeur>` (écritures ATT) → la lettre est associée au paramètre.

> ⚠️ De mauvaises valeurs d'injection peuvent endommager le moteur. Et toute écriture BLE
> désactive le bouton et le potentiomètre du boîtier.
