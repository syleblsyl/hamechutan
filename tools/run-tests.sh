#!/usr/bin/env bash
# Runs the JVM unit tests of the pure-Kotlin core (business logic, database, backup, reports)
# against a real SQLite engine (xerial sqlite-jdbc). Requires the toolchain from tools/setup-toolchain.sh
# and Ubuntu packages: junit4 libxerial-sqlite-jdbc-java libxerial-sqlite-jdbc-jni libslf4j-java libandroid-json-org-java
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
# The same goes for the license store (LicensePlatform.kt / LicenseConfig.kt).
PLATFORM=app/src/main/java/il/hamechutan/app/platform
"$TC/kotlinc/bin/kotlinc" app/src/main/java/il/hamechutan/app/core "$PLATFORM/PinManager.kt" "$PLATFORM/LicensePlatform.kt" "$PLATFORM/LicenseConfig.kt" app/src/test/java \
  -cp "$CP:$TC/android-34.jar" -d "$OUT/classes" -jvm-target 17 -nowarn 2>&1 | grep -v "^Picked up JAVA_TOOL_OPTIONS" || true
[ -d "$OUT/classes/il" ] || { echo "compilation failed"; exit 1; }

# License server (Google Apps Script code, run under Node with in-memory fakes) + an HTTP harness that serves it
# like Google does, for the end-to-end test of the app's license client (LicenseTest.endToEndWithRealServerCode).
if command -v node >/dev/null 2>&1; then
  echo "== license server tests (Node $(node --version))"
  TZ=Asia/Jerusalem node --test server/license/test/*.test.js 2>&1 | tail -n 9
  [ "${PIPESTATUS[0]}" -eq 0 ] || { echo "license server tests failed"; exit 1; }
  node server/license/test/serve.js > "$OUT/license-server.txt" 2>&1 &
  E2E_PID=$!
  trap 'kill $E2E_PID 2>/dev/null || true' EXIT
  for _ in $(seq 100); do grep -q '^PORT ' "$OUT/license-server.txt" && break; sleep 0.1; done
  grep -q '^PORT ' "$OUT/license-server.txt" || { echo "license test server did not start:"; cat "$OUT/license-server.txt"; exit 1; }
  export LICENSE_E2E_BASE="http://127.0.0.1:$(awk '/^PORT /{print $2}' "$OUT/license-server.txt")"
  export LICENSE_E2E_PUBKEY="$(awk '/^PORT /{print $4}' "$OUT/license-server.txt")"
else
  echo "== Node not found: license server tests and the end-to-end license test are skipped"
fi

TESTS=$(cd "$OUT/classes" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
echo "== running: $TESTS"
# Ubuntu's sqlite-jdbc ships its native part separately (libxerial-sqlite-jdbc-jni). Point to it explicitly:
# JDKs that are not from the Ubuntu archive (e.g. Temurin on GitHub runners) do not search that folder.
JNI_DIR=/usr/lib/$(uname -m)-linux-gnu/jni
[ -f "$JNI_DIR/libsqlitejdbc.so" ] || { echo "Missing $JNI_DIR/libsqlitejdbc.so (apt install libxerial-sqlite-jdbc-jni)"; exit 1; }
java -Dorg.sqlite.lib.path="$JNI_DIR" -Dorg.sqlite.lib.name=libsqlitejdbc.so \
  -cp "$OUT/classes:app/src/test/resources:$CP:$TC/kotlinc/lib/kotlin-stdlib.jar:$TC/android-34.jar" org.junit.runner.JUnitCore $TESTS 2>&1 \
  | grep -v "^Picked up JAVA_TOOL_OPTIONS" | tee "$OUT/results.txt"
grep -q "^OK (" "$OUT/results.txt"
