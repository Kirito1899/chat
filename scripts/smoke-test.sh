#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
source .local/dev-env.sh
TOKEN_PEPPER_BASE64="${TOKEN_PEPPER_BASE64//$'\r'/}"
BASE="https://127.0.0.1:8443"
CURL=(curl --ssl-no-revoke --cacert .local/tls/ca.crt -sS --fail)
EMAIL="device-$(date +%s)@example.com"
USERNAME="device_$(date +%s | tail -c 9)"
PASSWORD="Device-Smoke-Password-1"
NEW_PASSWORD="Device-Smoke-Password-2"
DEVICE_ID="$(python -c 'import uuid; print(uuid.uuid4())')"

INVITE="$("${CURL[@]}" -H "X-Admin-Token: $ADMIN_TOKEN" -X POST "$BASE/v1/admin/invites" | python -c 'import sys,json; print(json.load(sys.stdin)["inviteCode"])')"
"${CURL[@]}" -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/register" \
    --data "$(python -c 'import json,sys; print(json.dumps({"inviteCode":sys.argv[1],"email":sys.argv[2],"username":sys.argv[3],"password":sys.argv[4]}))' "$INVITE" "$EMAIL" "$USERNAME" "$PASSWORD")" >/dev/null
printf 'stage: registered\n'
sleep 1
CODE="$(curl -sS --fail 'http://127.0.0.1:8025/api/v1/messages?limit=50' | python -c '
import sys,json,urllib.request
email=sys.argv[1]
data=json.load(sys.stdin)
for item in data.get("messages", []):
    if any(x.get("Address") == email for x in item.get("To", [])):
        body=json.load(urllib.request.urlopen("http://127.0.0.1:8025/api/v1/message/" + item["ID"]))["Text"]
        import re
        match=re.search(r"([0-9]{8})", body)
        if match:
            print(match.group(1))
            break
' "$EMAIL")"
[[ "$CODE" =~ ^[0-9]{8}$ ]]
printf 'stage: verification mail received\n'
"${CURL[@]}" -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/verify-email" \
    --data "$(python -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"code":sys.argv[2]}))' "$EMAIL" "$CODE")" >/dev/null
printf 'stage: verified\n'

LOGIN="$("${CURL[@]}" -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/login" \
    --data "$(python -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2],"deviceId":sys.argv[3]}))' "$EMAIL" "$PASSWORD" "$DEVICE_ID")")"
ACCESS="$(printf '%s' "$LOGIN" | python -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')"
printf 'stage: logged in\n'
"${CURL[@]}" -H "Authorization: Bearer $ACCESS" "$BASE/v1/users/by-username/$USERNAME" | python -c 'import sys,json; d=json.load(sys.stdin); assert d["username"] == sys.argv[1]' "$USERNAME"
printf 'stage: search passed\n'

"${CURL[@]}" -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/recovery" \
    --data "$(python -c 'import json,sys; print(json.dumps({"email":sys.argv[1]}))' "$EMAIL")" >/dev/null
printf 'stage: recovery requested\n'
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
printf 'stage: recovery mail received\n'
"${CURL[@]}" -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/reset" \
    --data "$(python -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"code":sys.argv[2],"newPassword":sys.argv[3]}))' "$EMAIL" "$CODE" "$NEW_PASSWORD")" >/dev/null
"${CURL[@]}" -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/login" \
    --data "$(python -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2],"deviceId":sys.argv[3]}))' "$EMAIL" "$NEW_PASSWORD" "$DEVICE_ID")" | python -c 'import sys,json; d=json.load(sys.stdin); assert d["username"] == sys.argv[1]' "$USERNAME" >/dev/null

printf 'Smoke flow passed. Email: %s Username: %s\n' "$EMAIL" "$USERNAME"
printf 'Local test password: %s\n' "$NEW_PASSWORD"
