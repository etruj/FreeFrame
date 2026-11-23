#!/usr/bin/env bash
# Builds the FreeFrame APK from app/ without Gradle or Android Studio:
# just the SDK build-tools and the Android 5.1 android.jar. Every path can be
# overridden from the environment.
set -euo pipefail
cd "$(dirname "$0")/.."

BT="${BUILD_TOOLS:-${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/36.0.0}"   # d8, aapt2, zipalign, apksigner
JAR="${ANDROID_JAR:-${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-22/android.jar}"
APP=app
OUT=build
APK=build/FreeFrame.apk
KEY="${KEYSTORE:-freeframe.keystore}"                 # made on the first build; keep it, keep it private
PASS="${KEYSTORE_PASS:-freeframe}"
VERSION=4.0
CODE=22

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$APP/AndroidManifest.xml" -A "$APP/assets" "$OUT/res.zip" \
  --min-sdk-version 22 --target-sdk-version 22 --version-code "$CODE" --version-name "$VERSION"

javac -nowarn -source 8 -target 8 -bootclasspath "$JAR" -d "$OUT/classes" \
  $(find "$APP/src" -name '*.java') 2>&1 | grep -v '^warning: \[options\]' || true
"$BT/d8" --min-api 22 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

(cd "$OUT/dex" && zip -q -j ../base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

# A new key is made on the first build; keep it, or later builds can't update the app in place.
[ -f "$KEY" ] || keytool -genkeypair -keystore "$KEY" -storepass "$PASS" -keypass "$PASS" \
  -alias frame -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=FreeFrame" 2>/dev/null
"$BT/apksigner" sign --ks "$KEY" --ks-pass "pass:$PASS" --min-sdk-version 22 --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 22 "$APK"
rm -f "$APK.idsig"
# A versioned copy to keep and to put on other tablets.
mkdir -p release
cp "$APK" "release/FreeFrame-$VERSION.apk"
ls -l "$APK" "release/FreeFrame-$VERSION.apk"
