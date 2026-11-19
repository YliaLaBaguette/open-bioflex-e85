# Application Android — Open-Bioflex-E85

Application en **Java pur**, interface construite par code, **sans Gradle** : un seul fichier
source, [`src/xyz/ylia/bioflex/MainActivity.java`](src/xyz/ylia/bioflex/MainActivity.java).
Elle scanne le BLE, se connecte au boîtier, lit la trame d'état (commande `L`) environ une
fois par seconde et écrit les réglages. Le mode d'emploi est dans le
[guide d'utilisation](../docs/GUIDE-APP.md).

## Ce que contient le code

- **Tableau de bord** : jauges éthanol (zones SP / MIXTE / E85) et température (bleu / rouge,
  coupée au seuil de départ à froid), badge « À froid » / « À chaud ».
- **Réglages** : seuil, enrichissements froid / chaud (paliers de 5 %, bornés à 0–50 %),
  consigne de démarrage. *Appliquer* affiche un récapitulatif *ancienne → nouvelle*, écrit
  champ par champ, puis compare l'écho du boîtier à la commande envoyée.
- **Mode aperçu** : tableau de bord avec des données fictives, sans connexion.
- **Thème clair / nuit** : bouton ☾ / ☀ dans l'en-tête ; choix mémorisé (`SharedPreferences`),
  l'UI est reconstruite sans couper la liaison Bluetooth (`loadTheme()` + `buildUi()`).
- **Avancé** : journal complet (scan, événements GATT, notifications hex + ASCII, trame,
  décodage) exportable dans *Téléchargements*, écriture d'une commande brute, réécriture
  d'un champ à l'identique, *Restaurer* (valeurs d'origine de la session).

## Build

En pratique, GitHub Actions construit l'APK et le publie dans une
[release](https://github.com/YliaLaBaguette/open-bioflex-e85/releases) à chaque tag `vX.Y.Z`
(voir [`release.yml`](../.github/workflows/release.yml)). Le numéro de version vient du tag.

Build local — prérequis : Android **build-tools 34** + **platform android-34**, un **JDK 17**
(le JDK 25 fait planter `d8`) :

```sh
SDK=/chemin/android-sdk JAVA_HOME=/chemin/jdk-17 bash build.sh
# -> open-bioflex-e85.apk
```

Étapes : `aapt2 link` (manifeste → APK de base + `R.java`) · `javac` (classpath
`android.jar`) · `d8` (→ `classes.dex`) · injection du dex · `zipalign` · `apksigner`.

## Signature

Les releases sont signées avec une **clé privée** conservée dans les secrets du dépôt
(`SIGNING_KEYSTORE_B64`, `SIGNING_KEYSTORE_PASSWORD`) : la signature reste identique d'une
version à l'autre, les mises à jour s'installent par-dessus. Un build local utilise une clé
de test créée sur place ; l'installer demande de désinstaller d'abord la version publiée.

## Notes techniques

- `minSdk 26`, `targetSdk 34`.
- Permissions : `BLUETOOTH_SCAN` (+ `neverForLocation`) et `BLUETOOTH_CONNECT` sur
  Android 12+ ; `ACCESS_FINE_LOCATION` en dessous.
- Les 3 familles de modules BLE et leur couple écriture / notification sont dans
  `MainActivity.MODULES`.
- Fin de trame = 500 ms sans nouvelle notification (`FRAME_IDLE_MS`).
- Format d'écriture par champ : `writeFormat()` ; pas des réglages : tableau `SETTINGS`.
