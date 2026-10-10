#!/usr/bin/env bash
# Builds a signed, installable APK of "המחותן" without Gradle (see README.md for why).
#   ./build.sh            -> build/outputs/HaMechutan-<version>.apk
# Requires the toolchain installed by tools/setup-toolchain.sh (Kotlin, android.jar, aapt2, R8/D8)
# and Ubuntu packages: openjdk-17+ (or 21), zipalign, apksigner, zip.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=$(grep '^versionCode=' version.properties | cut -d= -f2 | tr -d '[:space:]')
VERSION_NAME=$(grep '^versionName=' version.properties | cut -d= -f2 | tr -d '[:space:]')
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || { echo "version.properties is missing versionCode/versionName"; exit 1; }
MIN_SDK=26
TARGET_SDK=34

TC="${TOOLCHAIN:-/opt/tc}"
ANDROID_JAR="$TC/android-34.jar"
AAPT2="$TC/bin/aapt2"
R8_JAR="$TC/jadx/lib/jadx-1.5.1-all.jar"   # contains com.android.tools.r8 (R8/D8 8.5)
KOTLINC="$TC/kotlinc/bin/kotlinc"
STDLIB="$TC/kotlinc/lib/kotlin-stdlib.jar"
for f in "$ANDROID_JAR" "$AAPT2" "$R8_JAR" "$KOTLINC" "$STDLIB"; do
  [ -e "$f" ] || { echo "Missing toolchain file: $f (run tools/setup-toolchain.sh)"; exit 1; }
done

# Release builds must talk to the license server (see server/license/README.md).
if [ "${REQUIRE_LICENSE:-false}" = "true" ]; then
  CFG=app/src/main/java/il/hamechutan/app/platform/LicenseConfig.kt
  if ! grep -q 'SERVER_URL = "https://script.google.com/macros/s/' "$CFG" || ! grep -q 'PUBLIC_KEY = "MII' "$CFG"; then
    echo "ERROR: $CFG has no license server URL / public key. Run tools/license-keys.sh <web-app-url>."; exit 1
  fi
fi

SRC=app/src/main
OUT=build/apk
rm -rf "$OUT"; mkdir -p "$OUT"/{gen,rclasses,kclasses,dex} build/outputs
quiet() { grep -v "^Picked up JAVA_TOOL_OPTIONS" || true; }

echo "== 1/7 resources (aapt2)"
# The source manifest has no package attribute (Gradle/AGP 8 style); aapt2 needs it.
APP_ID="il.hamechutan.app"
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$APP_ID\">|" \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
grep -q "package=\"$APP_ID\"" "$OUT/AndroidManifest.xml" || { echo "manifest package injection failed"; exit 1; }
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$AAPT2" link -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" -o "$OUT/base.apk" "$OUT/res.zip" \
  -A "$SRC/assets" --java "$OUT/gen" --proguard "$OUT/aapt-rules.pro" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name "$VERSION_NAME" --auto-add-overlay

echo "== 2/7 R.java"
javac -nowarn --release 11 -d "$OUT/rclasses" $(find "$OUT/gen" -name '*.java') 2>&1 | quiet

echo "== 3/7 Kotlin"
"$KOTLINC" "$SRC/java" -cp "$ANDROID_JAR:$OUT/rclasses" -d "$OUT/kclasses" -jvm-target 11 -nowarn 2>&1 | quiet | tee "$OUT/kotlinc.log"
if grep -q "error:" "$OUT/kotlinc.log" || [ ! -d "$OUT/kclasses/il" ]; then echo "Kotlin compilation failed"; exit 1; fi

echo "== 4/7 R8 (shrink + dex)"
(cd "$OUT/kclasses" && jar cf ../app-classes.jar . 2>&1 | quiet)
(cd "$OUT/rclasses" && jar cf ../r-classes.jar . 2>&1 | quiet)
java -cp "$R8_JAR" com.android.tools.r8.R8 --release --min-api $MIN_SDK --lib "$ANDROID_JAR" \
  --pg-conf app/proguard-rules.pro --pg-conf "$OUT/aapt-rules.pro" --pg-map-output "$OUT/mapping.txt" \
  --output "$OUT/dex" "$OUT/app-classes.jar" "$OUT/r-classes.jar" "$STDLIB" 2>&1 | quiet | grep -v "META-INF/MANIFEST.MF" || true
ls "$OUT"/dex/classes*.dex >/dev/null

echo "== 5/7 package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes*.dex)
zipalign -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== 6/7 sign"
KS_DIR="${SIGNING_DIR:-signing}"
if [ ! -f "$KS_DIR/release.jks" ] && [ "${REQUIRE_SIGNING_KEY:-false}" = "true" ]; then
  echo "ERROR: signing key $KS_DIR/release.jks is required (an APK signed with a new key cannot update the installed app)"; exit 1
fi
if [ ! -f "$KS_DIR/release.jks" ]; then
  echo "   creating a new signing key in $KS_DIR/ (keep it! updates must be signed with the same key)"
  mkdir -p "$KS_DIR"; chmod 700 "$KS_DIR"
  PASS=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
  printf '%s' "$PASS" > "$KS_DIR/password.txt"; chmod 600 "$KS_DIR/password.txt"
  keytool -genkeypair -keystore "$KS_DIR/release.jks" -storetype PKCS12 -alias hamechutan -keyalg RSA -keysize 3072 \
    -validity 36500 -dname "CN=HaMechutan, O=Personal, C=IL" -storepass "$PASS" -keypass "$PASS" 2>&1 | quiet
fi
APK="build/outputs/HaMechutan-$VERSION_NAME.apk"
apksigner sign --ks "$KS_DIR/release.jks" --ks-key-alias hamechutan --ks-pass "file:$KS_DIR/password.txt" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk" 2>&1 | quiet

echo "== 7/7 verify"
# Write to files first: piping straight into `head` can kill the producer with SIGPIPE (exit 141 under pipefail).
apksigner verify --verbose "$APK" > "$OUT/verify.txt" 2>&1
quiet < "$OUT/verify.txt" | sed -n 1,5p
"$AAPT2" dump badging "$APK" > "$OUT/badging.txt"
sed -n 1,4p "$OUT/badging.txt"
ls -la "$APK"
echo "OK: $APK"
