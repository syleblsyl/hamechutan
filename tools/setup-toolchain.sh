#!/usr/bin/env bash
# Installs the build toolchain used by build.sh into /opt/tc (or $TOOLCHAIN) on Ubuntu 24.04.
# Everything comes from GitHub releases/raw files and the Ubuntu archive (Google Maven is not required).
set -euo pipefail
TC="${TOOLCHAIN:-/opt/tc}"
mkdir -p "$TC/bin"
cd "$TC"

echo "== Ubuntu packages (JDK, signing tools, test libraries)"
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk-headless zip unzip zipalign apksigner \
  junit4 libxerial-sqlite-jdbc-java libslf4j-java libandroid-json-org-java

echo "== Kotlin compiler 2.0.21"
[ -d kotlinc ] || { curl -sSL -o kotlin.zip https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip && unzip -q kotlin.zip && rm kotlin.zip; }

echo "== android.jar (API 34) from the Sable/android-platforms repository"
[ -f android-34.jar ] || curl -sSL -o android-34.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar

echo "== R8/D8 8.5 (bundled inside the jadx release)"
[ -d jadx ] || { curl -sSL -o jadx.zip https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip && unzip -q jadx.zip -d jadx && rm jadx.zip; }

echo "== aapt2 (linux x86_64, bundled inside the Apktool release)"
if [ ! -x bin/aapt2 ]; then
  curl -sSL -o apktool.jar https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar
  unzip -q -o -j apktool.jar prebuilt/linux/aapt2_64 -d bin && mv bin/aapt2_64 bin/aapt2 && chmod +x bin/aapt2
fi

"$TC/bin/aapt2" version
java -cp "$TC/jadx/lib/jadx-1.5.1-all.jar" com.android.tools.r8.R8 --version 2>/dev/null | head -1
"$TC/kotlinc/bin/kotlinc" -version 2>&1 | grep kotlinc
echo "Toolchain ready in $TC"
