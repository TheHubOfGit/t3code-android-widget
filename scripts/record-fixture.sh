#!/usr/bin/env bash
# Record a sanitized shell snapshot from a live T3 Code server as a test fixture.
# Re-run whenever T3 Code ships a new version (HANDOFF.md §8, rule 8).
#
#   T3_URL=http://127.0.0.1:3773 T3_TOKEN=... scripts/record-fixture.sh [name]
#
# Issue a short-lived read-only token with:
#   t3 auth session issue --scope orchestration:read --ttl 1d --label fixture --token-only
set -euo pipefail

: "${T3_URL:?set T3_URL}"
: "${T3_TOKEN:?set T3_TOKEN}"
name="${1:-shell_real}"
here="$(cd "$(dirname "$0")" && pwd)"
dest="$here/../app/src/test/resources/fixtures"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsS -H "x-t3-orchestration-protocol: 2" "$T3_URL/.well-known/t3/environment" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("server", d.get("serverVersion"), "protocol", d.get("orchestrationProtocolVersion"))'

curl -fsS -o "$tmp/raw.json" \
  -H "Authorization: Bearer $T3_TOKEN" \
  -H "x-t3-orchestration-protocol: 2" \
  "$T3_URL/api/orchestration/shell?omitIdleSubagents=true&omitOlderSettled=true"

mkdir -p "$dest"
python3 -I "$here/sanitize_shell.py" "$tmp/raw.json" "$dest/$name.json" --max-threads 8
echo "wrote $dest/$name.json"
