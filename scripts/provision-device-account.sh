#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
source .local/dev-env.sh
BASE="https://127.0.0.1:8443"
CURL=(curl --ssl-no-revoke --cacert .local/tls/ca.crt -sS --fail)
EMAIL="phone-$(date +%s)@example.com"
USERNAME="phone_$(date +%s | tail -c 9)"
PASSWORD="Device-UI-Password-1"

INVITE="$("${CURL[@]}" -H "X-Admin-Token: $ADMIN_TOKEN" -X POST "$BASE/v1/admin/invites" | python -c 'import sys,json; print(json.load(sys.stdin)["inviteCode"])')"
"${CURL[@]}" -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/register" \
    --data "$(python -c 'import json,sys; print(json.dumps({"inviteCode":sys.argv[1],"email":sys.argv[2],"username":sys.argv[3],"password":sys.argv[4]}))' "$INVITE" "$EMAIL" "$USERNAME" "$PASSWORD")" >/dev/null
sleep 1
CODE="$(curl -sS --fail 'http://127.0.0.1:8025/api/v1/messages?limit=50' | python -c '
import sys,json,urllib.request,re
email=sys.argv[1]
data=json.load(sys.stdin)
for item in data.get("messages", []):
    if any(x.get("Address") == email for x in item.get("To", [])):
        body=json.load(urllib.request.urlopen("http://127.0.0.1:8025/api/v1/message/" + item["ID"]))["Text"]
        match=re.search(r"([0-9]{8})", body)
        if match:
            print(match.group(1))
            break
' "$EMAIL")"
[[ "$CODE" =~ ^[0-9]{8}$ ]]
"${CURL[@]}" -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/verify-email" \
    --data "$(python -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"code":sys.argv[2]}))' "$EMAIL" "$CODE")" >/dev/null
printf 'Email: %s\nUsername: %s\nLocal test password: %s\n' "$EMAIL" "$USERNAME" "$PASSWORD"
