#!/usr/bin/env zsh
# Builds and signs AirPlay.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: ./build.sh [--install]
#   --install pushes it to the Fire TV at $FIRETV (ip:port, e.g. FIRETV=192.168.1.20:5555), grants
#   the overlay permission so the player can open on its own, and starts the receiver.
# Needs an Android SDK with a platform (android.jar) and build-tools; JDK 11+ for javac.
#   ANDROID_SDK   SDK folder (default: $ANDROID_HOME, then the usual macOS locations)
#   BUILD_TOOLS   build-tools version (default: newest installed)
#   PLATFORM      platform for android.jar (default: android-33, else newest installed)
# Signing: keystore.jks is created on first build and is never committed. Keep it (and back it up)
# to sign updates; set KEYSTORE / KEYSTORE_PASS to use your own.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-${ANDROID_HOME:-}}"
for guess in "$HOME/Library/Android/sdk" "$HOME/Library/Developer/Xamarin/android-sdk-macosx" "$HOME/Android/Sdk"; do
    [[ -z "$SDK" && -d "$guess" ]] && SDK="$guess"
done
[[ -d "$SDK" ]] || { echo "Android SDK not found: set ANDROID_SDK" >&2; exit 1; }
BT="$SDK/build-tools/${BUILD_TOOLS:-$(ls "$SDK/build-tools" | sort -V | tail -1)}"
PLATFORM="${PLATFORM:-android-33}"
[[ -f "$SDK/platforms/$PLATFORM/android.jar" ]] || PLATFORM="$(ls "$SDK/platforms" | grep -E '^android-[0-9]+$' | sort -V | tail -1)"
JAR="$SDK/platforms/$PLATFORM/android.jar"
ADB="$SDK/platform-tools/adb"
KEYSTORE="${KEYSTORE:-keystore.jks}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
OUT="${OUT:-build}"
LIBS=(libs/*.jar)

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/root"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java "$OUT/gen" \
    -o "$OUT/unsigned.apk" "$OUT/res.zip"

# third_party/: the FairPlay + pairing code from java-airplay (MIT) and an ALAC decoder (BSD).
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
    -bootclasspath "$JAR" -classpath "$BT/core-lambda-stubs.jar:${(j/:/)LIBS}" -d "$OUT/classes" \
    $(find src third_party "$OUT/gen" -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class') $LIBS
(cd "$OUT/dex" && zip -q ../unsigned.apk classes*.dex)
# FairPlay's lookup tables are read as class-loader resources from the APK root.
cp third_party/resources/table_* "$OUT/root/"
(cd "$OUT/root" && zip -q ../unsigned.apk table_*)

"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [[ ! -f "$KEYSTORE" ]]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
        -alias airplay -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AirPlay Receiver" >/dev/null
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" --out "$OUT/AirPlay.apk" "$OUT/aligned.apk"
echo "Built $OUT/AirPlay.apk"

if [[ "${1:-}" == "--install" ]]; then
    [[ -n "${FIRETV:-}" ]] || { echo "Set FIRETV to your Fire TV's address, e.g. FIRETV=192.168.1.20:5555" >&2; exit 1; }
    "$ADB" connect "$FIRETV" >/dev/null
    "$ADB" -s "$FIRETV" install -r --no-incremental "$OUT/AirPlay.apk"
    "$ADB" -s "$FIRETV" shell appops set com.pritesh.airplay SYSTEM_ALERT_WINDOW allow
    "$ADB" -s "$FIRETV" shell am start -n com.pritesh.airplay/.MainActivity
fi
