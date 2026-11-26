#!/usr/bin/env bash
set -euo pipefail

source /opt/secure-messenger/deploy/load-env.sh
load_env /etc/secure-messenger/server.env
USERNAME="$1"
EMAIL="${LETSENCRYPT_EMAIL%@*}+${USERNAME}@${LETSENCRYPT_EMAIL#*@}"
PASSWORD_HASH=$(/opt/secure-messenger/deploy/hash-password.sh "$(openssl rand -hex 32)")
runuser -u postgres -- psql --quiet --dbname=secure_messenger <<SQL
UPDATE accounts SET password_hash = '$PASSWORD_HASH' WHERE email = '$EMAIL';
UPDATE sessions SET revoked_at = COALESCE(revoked_at, now()) WHERE account_id IN (SELECT id FROM accounts WHERE email = '$EMAIL');
UPDATE devices SET revoked_at = COALESCE(revoked_at, now()) WHERE account_id IN (SELECT id FROM accounts WHERE email = '$EMAIL');
SQL
printf 'Test account locked with a random password: %s\n' "$USERNAME"
