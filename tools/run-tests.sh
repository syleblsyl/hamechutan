#!/usr/bin/env bash
# Runs the JVM unit tests of the pure-Kotlin core (business logic, database, backup, reports)
# against a real SQLite engine (xerial sqlite-jdbc). Requires the toolchain from tools/setup-toolchain.sh
# and Ubuntu packages: junit4 libxerial-sqlite-jdbc-java libslf4j-java libandroid-json-org-java
set -euo pipefail
cd "$(dirname "$0")/.."
TC="${TOOLCHAIN:-/opt/tc}"
OUT=build/test
rm -rf "$OUT"; mkdir -p "$OUT/classes"

# The core must stay free of Android APIs so that it can be tested here.
if grep -rn "import android\." app/src/main/java/il/hamechutan/app/core >/dev/null; then
  echo "ERROR: core package must not import android.*"; grep -rn "import android\." app/src/main/java/il/hamechutan/app/core; exit 1
fi

J=/usr/share/java
CP="$J/junit4.jar:$J/hamcrest-core.jar:$J/json-android.jar:$J/sqlite-jdbc.jar:$J/slf4j-api.jar"
echo "== compiling core + tests"
# PinManager is platform code but depends only on the SharedPreferences interface, so it is tested here too.
"$TC/kotlinc/bin/kotlinc" app/src/main/java/il/hamechutan/app/core app/src/main/java/il/hamechutan/app/platform/PinManager.kt app/src/test/java \
  -cp "$CP:$TC/android-34.jar" -d "$OUT/classes" -jvm-target 17 -nowarn 2>&1 | grep -v "^Picked up JAVA_TOOL_OPTIONS" || true
[ -d "$OUT/classes/il" ] || { echo "compilation failed"; exit 1; }

TESTS=$(cd "$OUT/classes" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
echo "== running: $TESTS"
java -cp "$OUT/classes:app/src/test/resources:$CP:$TC/kotlinc/lib/kotlin-stdlib.jar:$TC/android-34.jar" org.junit.runner.JUnitCore $TESTS 2>&1 \
  | grep -v "^Picked up JAVA_TOOL_OPTIONS" | tee "$OUT/results.txt"
grep -q "^OK (" "$OUT/results.txt"
