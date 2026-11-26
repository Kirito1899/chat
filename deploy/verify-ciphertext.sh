#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${EXPECTED_TEXT:-}" ]]; then
    printf 'EXPECTED_TEXT is required\n' >&2
    exit 1
fi
export EXPECTED_TEXT
runuser -u postgres -- psql -tAqc "SELECT encode(ciphertext, 'hex') FROM message_queue WHERE expires_at > now()" secure_messenger | python3 -c '
import os, sys
expected = os.environ["EXPECTED_TEXT"].encode()
rows = [line.strip() for line in sys.stdin if line.strip()]
assert rows, "No queued ciphertext envelopes found"
for row in rows:
    ciphertext = bytes.fromhex(row)
    assert expected not in ciphertext, "Plaintext message content found in PostgreSQL"
print(f"Verified {len(rows)} queued ciphertext messages; plaintext is absent")
'
