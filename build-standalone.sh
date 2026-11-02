#!/usr/bin/env bash
#
# Build TapTrak into an installable APK using only standalone tools —
# no Android Studio, no Gradle, no SDK Manager. You need, on your PATH or set below:
#
#   JAVA (JDK 17)   - javac + java
#   ANDROID_JAR     - path to an android.jar for API 34 (platforms/android-34/android.jar)
#   AAPT2           - aapt2 binary
#   D8              - d8 (script or: java -cp d8.jar com.android.tools.r8.D8)
#   APKSIGNER       - apksigner (and a debug/release keystore)
#   ZIPALIGN        - zipalign (optional but recommended)
#
# All of these ship inside the official Android "command-line tools" + a build-tools
# package (a few hundred MB total). Point the variables below at them and run this.
#
# This mirrors how the project was intended to be compiled without the full IDE.
set -euo pipefail

# ---- configure these -------------------------------------------------------
ANDROID_JAR="${ANDROID_JAR:?set ANDROID_JAR to platforms/android-34/android.jar}"
AAPT2="${AAPT2:-aapt2}"
D8="${D8:-d8}"
APKSIGNER="${APKSIGNER:-apksigner}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
# ---------------------------------------------------------------------------

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main"
OUT="$ROOT/out"
rm -rf "$OUT"; mkdir -p "$OUT/compiled_res" "$OUT/classes" "$OUT/dex"

echo "[1/6] Compile resources"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/compiled_res.zip"

echo "[2/6] Link resources -> base APK + R.java"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT" \
  --min-sdk-version 24 --target-sdk-version 34 \
  "$OUT/compiled_res.zip"

echo "[3/6] Compile Java (framework only, no AndroidX)"
R_JAVA="$(find "$OUT" -name R.java)"
javac -source 17 -target 17 -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  $(find "$SRC/java" -name '*.java') "$R_JAVA"

echo "[4/6] Dex"
"$D8" --min-api 24 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "[5/6] Assemble APK"
cd "$OUT"
cp base.apk unsigned.apk
( cd dex && zip -q "$OUT/unsigned.apk" classes.dex )
"$ZIPALIGN" -f 4 unsigned.apk aligned.apk 2>/dev/null || cp unsigned.apk aligned.apk

echo "[6/6] Sign"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KEY_ALIAS" \
  --out "$ROOT/TapTrak.apk" aligned.apk
"$APKSIGNER" verify "$ROOT/TapTrak.apk" && echo "OK -> $ROOT/TapTrak.apk"
