#!/usr/bin/env bash
set -euo pipefail

source /opt/secure-messenger/deploy/load-env.sh
load_env /etc/secure-messenger/server.env
for command in curl python3 openssl; do
    command -v "$command" >/dev/null || {
        printf 'Required command is missing: %s\n' "$command" >&2
        exit 1
    }
done
BASE="https://$PUBLIC_DOMAIN:8443"
EMAIL="$LETSENCRYPT_EMAIL"
USERNAME="smoke_$(date +%s | tail -c 9)"
PASSWORD="$(openssl rand -hex 16)"
DEVICE_ID="$(cat /proc/sys/kernel/random/uuid)"

INVITE=$(curl -fsS -H "X-Admin-Token: $ADMIN_TOKEN" -X POST "$BASE/v1/admin/invites" | python3 -c 'import json,sys; print(json.load(sys.stdin)["inviteCode"])')
PAYLOAD=$(python3 -c 'import json,sys; print(json.dumps({"inviteCode":sys.argv[1],"email":sys.argv[2],"username":sys.argv[3],"password":sys.argv[4]}))' "$INVITE" "$EMAIL" "$USERNAME" "$PASSWORD")
curl -fsS -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/register" --data "$PAYLOAD" >/dev/null
export AFTER_EPOCH="$(date +%s)" EXPECTED_SUBJECT="Подтверждение аккаунта" SMTP_HOST SMTP_USERNAME SMTP_PASSWORD
CODE=$(python3 /opt/secure-messenger/deploy/imap-code.py)
VERIFY=$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"code":sys.argv[2]}))' "$EMAIL" "$CODE")
curl -fsS -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/verify-email" --data "$VERIFY" >/dev/null
LOGIN=$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2],"deviceId":sys.argv[3]}))' "$EMAIL" "$PASSWORD" "$DEVICE_ID")
TOKENS=$(curl -fsS -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/login" --data "$LOGIN")
ACCESS_TOKEN=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])' <<<"$TOKENS")
curl -fsS -H "Authorization: Bearer $ACCESS_TOKEN" "$BASE/v1/users/by-username/$USERNAME" >/dev/null

curl -fsS -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/recovery" \
    --data "$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1]}))' "$EMAIL")" >/dev/null
export AFTER_EPOCH="$(date +%s)" EXPECTED_SUBJECT="Восстановление доступа"
CODE=$(python3 /opt/secure-messenger/deploy/imap-code.py)
NEW_PASSWORD="$(openssl rand -hex 16)"
RESET=$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"code":sys.argv[2],"newPassword":sys.argv[3]}))' "$EMAIL" "$CODE" "$NEW_PASSWORD")
curl -fsS -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/reset" --data "$RESET" >/dev/null
DEVICE_ID="$(cat /proc/sys/kernel/random/uuid)"
LOGIN=$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2],"deviceId":sys.argv[3]}))' "$EMAIL" "$NEW_PASSWORD" "$DEVICE_ID")
TOKENS=$(curl -fsS -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/login" --data "$LOGIN")
python3 -c 'import json,sys; data=json.load(sys.stdin); assert data["username"] == sys.argv[1]' "$USERNAME" <<<"$TOKENS"
printf 'Production registration, real email verification, login and password recovery passed\n'
