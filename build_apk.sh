#!/usr/bin/env bash
# Build Open Ring into a signed APK without Gradle.
#
# Needs: a JDK (javac, keytool) plus Android build tools (aapt2, d8 or dx, zipalign, apksigner)
# and an android.jar (API 34 recommended). It finds them in $ANDROID_HOME / $ANDROID_SDK_ROOT,
# or in the Debian/Ubuntu packages:  sudo apt install aapt apksigner zipalign dalvik-exchange
# (then pass ANDROID_JAR=/path/to/android.jar, for example from the platforms/android-34 folder
# of any Android SDK). Usage:  ./build_apk.sh   ->  build/OpenRing.apk
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(pwd)
OUT="$ROOT/build"
SRC="$ROOT/app/src/main"
VERSION_CODE=22
VERSION_NAME=1.21
MIN_SDK=26
TARGET_SDK=34

find_tool() {
  local name=$1
  if [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    local sdk=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
    local hit
    hit=$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
    if [ -n "$hit" ] && [ -x "$hit$name" ]; then echo "$hit$name"; return; fi
  fi
  for d in /usr/lib/android-sdk/build-tools/*/ /usr/bin/; do
    if [ -x "$d$name" ]; then echo "$d$name"; return; fi
  done
  command -v "$name" || true
}

AAPT2=${AAPT2:-$(find_tool aapt2)}
D8=${D8:-$(find_tool d8)}
DX=${DX:-$(find_tool dx)}
ZIPALIGN=${ZIPALIGN:-$(find_tool zipalign)}
APKSIGNER=${APKSIGNER:-$(find_tool apksigner)}
if [ -z "${ANDROID_JAR:-}" ]; then
  sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  if [ -n "$sdk" ]; then ANDROID_JAR=$(ls "$sdk"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1); fi
fi
for v in AAPT2 ZIPALIGN APKSIGNER ANDROID_JAR; do
  if [ -z "${!v:-}" ]; then echo "missing $v (see the header of this script)"; exit 1; fi
done
if [ -z "$D8" ] && [ -z "$DX" ]; then echo "missing d8 or dx"; exit 1; fi

echo "aapt2:      $AAPT2"
echo "dexer:      ${D8:-$DX}"
echo "android.jar $ANDROID_JAR"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/res"

# The source manifest has no package attribute (Gradle supplies the namespace); add it here.
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.openring">#' \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

echo "== resources"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$AAPT2" link -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" -o "$OUT/base.apk" \
  --java "$OUT/gen" --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME "$OUT/res.zip"

echo "== java"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "== dex"
if [ -n "$D8" ]; then
  (cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
  "$D8" --release --min-api $MIN_SDK --lib "$ANDROID_JAR" --output "$OUT" "$OUT/classes.jar"
else
  "$DX" --dex --min-sdk-version=$MIN_SDK --output="$OUT/classes.dex" "$OUT/classes"
fi

echo "== package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q unsigned.apk classes.dex)
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS=${KEYSTORE:-$ROOT/release.keystore}
if [ ! -f "$KS" ]; then
  echo "== creating a signing key at $KS (keep it to install updates over this build)"
  keytool -genkeypair -keystore "$KS" -storepass openring -keypass openring -alias openring \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Open Ring" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:${KS_PASS:-openring} --ks-key-alias openring \
  --out "$OUT/OpenRing.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify "$OUT/OpenRing.apk"
echo "built $OUT/OpenRing.apk"
