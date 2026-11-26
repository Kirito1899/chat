#!/usr/bin/env bash
set -euo pipefail

source /opt/secure-messenger/deploy/load-env.sh
load_env /etc/secure-messenger/server.env
USERNAME="$1"
PASSWORD="$2"
EMAIL="${LETSENCRYPT_EMAIL%@*}+${USERNAME}@${LETSENCRYPT_EMAIL#*@}"
PASSWORD_HASH=$(/opt/secure-messenger/deploy/hash-password.sh "$PASSWORD")
runuser -u postgres -- psql --quiet --dbname=secure_messenger <<SQL
INSERT INTO accounts(id, email, username, username_normalized, password_hash, email_verified)
VALUES (gen_random_uuid(), '$EMAIL', '$USERNAME', lower('$USERNAME'), '$PASSWORD_HASH', true);
SQL
printf '%s\n' "$EMAIL"
