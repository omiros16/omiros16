#!/usr/bin/env bash
# Builds the Gallery APK the same way as ../build.sh (no Android SDK or Gradle; tools from Maven Central)
# and shares its tool cache, signing key and packaging scripts.  Output: build/Gallery.apk
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION_CODE=1
VERSION_NAME=1.0
MIN_SDK=30
TARGET_SDK=34

M=https://repo1.maven.org/maven2
T=.tools
B=build/gallery
SRC=gallery/src/main
mkdir -p "$T"

fetch() { [ -f "$T/$2" ] || curl -fsSL --retry 5 --retry-all-errors -o "$T/$2" "$M/$1"; }
fetch org/apktool/apktool-cli/3.0.3/apktool-cli-3.0.3.jar apktool.jar
fetch org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar android-all.jar
fetch com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar dx.jar
fetch com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar apksig.jar
if [ ! -x "$T/prebuilt/linux/aapt2" ]; then
    (cd "$T" && unzip -oq apktool.jar prebuilt/linux/aapt2 prebuilt/android-framework.jar && chmod +x prebuilt/linux/aapt2)
fi
AAPT2="$T/prebuilt/linux/aapt2"
FRAMEWORK="$T/prebuilt/android-framework.jar"

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/signer" "$B/test"

echo "==> tests"
javac -nowarn -encoding UTF-8 -d "$B/test" $SRC/java/com/omiros/gallery/Sections.java gallery/test/SectionsTest.java
java -ea -cp "$B/test" com.omiros.gallery.SectionsTest

echo "==> resources"
"$AAPT2" compile --dir $SRC/res -o "$B/res.zip"
"$AAPT2" link -o "$B/base.apk" -I "$FRAMEWORK" \
    --manifest $SRC/AndroidManifest.xml \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    --version-code $VERSION_CODE --version-name $VERSION_NAME \
    --java "$B/gen" "$B/res.zip"

echo "==> java"
javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 -cp "$T/android-all.jar" -d "$B/classes" \
    $(find $SRC/java "$B/gen" -name '*.java')

echo "==> dex"
java -cp "$T/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=26 --output="$B/classes.dex" "$B/classes"

echo "==> package + sign"
python3 tools/package.py "$B/base.apk" "$B/classes.dex" "$B/unsigned.apk"
javac -nowarn -cp "$T/apksig.jar" -d "$B/signer" tools/Sign.java
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED \
    -cp "$B/signer:$T/apksig.jar" Sign keystore/timetable.p12 timetable timetable "$B/unsigned.apk" build/Gallery.apk

echo "==> done: build/Gallery.apk"
