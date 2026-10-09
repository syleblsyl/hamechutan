#!/usr/bin/env bash
# Prepares the license server for deployment and points the app at it.
#
#   ./tools/license-keys.sh                 # first time: creates the RSA key pair (license-keys/, NOT in git),
#                                           # puts the public key in LicenseConfig.kt and writes
#                                           # license-keys/Code.gs = server code + private key, to paste into Apps Script
#   ./tools/license-keys.sh <web-app-url>   # also sets the server URL in LicenseConfig.kt
#
# The private key exists only in license-keys/ and in the seller's Apps Script project. If it is lost,
# run this again after deleting license-keys/: new keys -> paste the new Code.gs -> release a new app version.
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=license-keys
CONFIG=app/src/main/java/il/hamechutan/app/platform/LicenseConfig.kt
URL="${1:-}"

mkdir -p "$DIR"; chmod 700 "$DIR"
if [ ! -f "$DIR/private.pem" ]; then
  echo "== creating a new RSA-2048 key pair in $DIR/"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$DIR/private.pem" 2>/dev/null
  chmod 600 "$DIR/private.pem"
fi
openssl pkey -in "$DIR/private.pem" -pubout -outform DER 2>/dev/null | base64 -w0 > "$DIR/public.b64"

if [ -n "$URL" ] && ! [[ "$URL" =~ ^https://script\.google\.com/macros/s/[A-Za-z0-9_-]+/exec$ ]]; then
  echo "ERROR: the URL should look like https://script.google.com/macros/s/<id>/exec"; exit 1
fi

python3 - "$DIR" "$CONFIG" "$URL" <<'PY'
import json, re, sys
d, config, url = sys.argv[1], sys.argv[2], sys.argv[3]
pub = open(f"{d}/public.b64").read().strip()
pem = open(f"{d}/private.pem").read()

s = open(config, encoding="utf-8").read()
s = re.sub(r'const val PUBLIC_KEY = ".*"', f'const val PUBLIC_KEY = "{pub}"', s)
if url:
    s = re.sub(r'const val SERVER_URL = ".*"', f'const val SERVER_URL = "{url}"', s)
open(config, "w", encoding="utf-8").write(s)

code = open("server/license/Code.gs", encoding="utf-8").read()
marker = "var PRIVATE_KEY = '';"
assert code.count(marker) == 1, "PRIVATE_KEY placeholder not found in Code.gs"
code = code.replace(marker, "var PRIVATE_KEY = " + json.dumps(pem) + ";")
open(f"{d}/Code.gs", "w", encoding="utf-8").write(code)
PY
chmod 600 "$DIR/Code.gs"
echo "== public key written to $CONFIG"
[ -n "$URL" ] && echo "== server URL written to $CONFIG"
echo "== $DIR/Code.gs is ready to paste into Apps Script (it contains the PRIVATE key — do not share or commit it)"
