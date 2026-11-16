#!/usr/bin/env bash
# Build manuel d'un APK debug, sans Gradle.
# Usage : SDK=/chemin/android-sdk JAVA_HOME=/chemin/jdk-17 bash build.sh
set -e
SDK="${SDK:-${ANDROID_HOME:-$HOME/android-sdk}}"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
APP="$(cd "$(dirname "$0")" && pwd)"
# JDK 17 pour javac + d8 (le JDK 25 fait planter d8)
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk-17}"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"
export PATH="$JAVA_HOME/bin:$PATH"
OUT="$APP/build"
PKG_DIR="$OUT/gen"

rm -rf "$OUT"; mkdir -p "$OUT/compiled" "$OUT/classes" "$PKG_DIR"

echo "[1/6] aapt2 compile ressources (icône, logo…)"
"$BT/aapt2.exe" compile --dir "$APP/res" -o "$OUT/res.zip"
echo "[2/6] aapt2 link (manifest + res -> apk de base + R.java)"
"$BT/aapt2.exe" link \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$PKG_DIR" \
  --min-sdk-version 26 --target-sdk-version 34 \
  -o "$OUT/base.apk" \
  "$OUT/res.zip"

echo "[3/6] javac"
find "$APP/src" "$PKG_DIR" -name '*.java' > "$OUT/srcs.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/srcs.txt" 2> "$OUT/javac.log" || { cat "$OUT/javac.log"; exit 1; }
cat "$OUT/javac.log" || true

echo "[4/6] d8 -> classes.dex"
CLASSES=$(find "$OUT/classes" -name '*.class')
"$BT/d8.bat" --min-api 26 --lib "$ANDROID_JAR" --output "$OUT" $CLASSES

echo "[5/6] assemble + zipalign"
cd "$OUT"
cp base.apk unsigned.apk
# injection de classes.dex dans l'apk via python (portable, pas besoin de zip)
python -I - "$OUT/unsigned.apk" "$OUT/classes.dex" <<'PY'
import sys, zipfile, shutil
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("classes.dex injecté")
PY
"$BT/zipalign.exe" -f -p 4 unsigned.apk aligned.apk

echo "[6/6] signature"
# Clé au choix : KS=/chemin/cle.jks KS_ALIAS=alias KS_PASS=motdepasse bash build.sh
# Par défaut : clé de test locale (hors dépôt), créée au premier build. Un APK signé avec
# une autre clé que celle des releases demande de désinstaller l'appli avant de l'installer.
KS="${KS:-$APP/local-test.keystore}"
KS_ALIAS="${KS_ALIAS:-test}"
export KS_PASS="${KS_PASS:-android}"
if [ ! -f "$KS" ]; then
  echo "clé de test locale créée : $KS"
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Test local" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" --ks-pass env:KS_PASS --key-pass env:KS_PASS \
  --out "$APP/open-bioflex-e85.apk" aligned.apk
"$BT/apksigner.bat" verify --print-certs "$APP/open-bioflex-e85.apk" | head -3
echo "OK -> $APP/open-bioflex-e85.apk"
