#!/usr/bin/env bash
set -euo pipefail

APP_ROOT=/opt/secure-messenger
CONFIG_ROOT=/etc/secure-messenger
DATA_ROOT=/var/lib/secure-messenger
SERVICE_USER=messenger

if [[ $EUID -ne 0 ]]; then
    printf 'Run this installer as root on the Linux host\n' >&2
    exit 1
fi
for command in java psql pg_isready runuser systemctl openssl; do
    command -v "$command" >/dev/null || {
        printf 'Required command is missing: %s\n' "$command" >&2
        exit 1
    }
done
JAVA_MAJOR=$(java -version 2>&1 | awk -F '"' 'NR == 1 { print $2 }' | cut -d. -f1)
if [[ ! "$JAVA_MAJOR" =~ ^[0-9]+$ || "$JAVA_MAJOR" -lt 21 ]]; then
    printf 'JDK 21 or newer is required\n' >&2
    exit 1
fi
if [[ ! -x "$APP_ROOT/bin/server" || ! -f "$APP_ROOT/lib/server.jar" ]]; then
    printf 'Server distribution is missing under %s\n' "$APP_ROOT" >&2
    exit 1
fi
if [[ ! -f "$CONFIG_ROOT/server.env" || ! -f "$CONFIG_ROOT/tls/server.p12" ]]; then
    printf 'Install server.env and tls/server.p12 under %s before running this installer\n' "$CONFIG_ROOT" >&2
    exit 1
fi
source "$APP_ROOT/deploy/load-env.sh"
load_env "$CONFIG_ROOT/server.env"
for variable in DATABASE_URL DATABASE_USER DATABASE_PASSWORD POSTGRES_PASSWORD ADMIN_TOKEN TOKEN_PEPPER_BASE64 MEDIA_STORAGE_PATH TLS_KEYSTORE_PATH TLS_KEY_ALIAS TLS_KEYSTORE_PASSWORD TLS_PRIVATE_KEY_PASSWORD SMTP_HOST SMTP_PORT SMTP_FROM SMTP_STARTTLS SMTP_SSL; do
    if [[ -z "${!variable:-}" ]]; then
        printf 'Missing required setting: %s\n' "$variable" >&2
        exit 1
    fi
done
if [[ ! "$DATABASE_USER" =~ ^[a-z_][a-z0-9_]*$ ]]; then
    printf 'DATABASE_USER must be a simple PostgreSQL role name\n' >&2
    exit 1
fi
if [[ ! "$DATABASE_PASSWORD" =~ ^[A-Za-z0-9_-]{32,}$ ]]; then
    printf 'DATABASE_PASSWORD must be a generated alphanumeric/_/- secret of at least 32 characters\n' >&2
    exit 1
fi
if [[ ${#ADMIN_TOKEN} -lt 32 ]]; then
    printf 'ADMIN_TOKEN must contain at least 32 characters\n' >&2
    exit 1
fi
if [[ "$MEDIA_STORAGE_PATH" != "$DATA_ROOT"/* ]]; then
    printf 'MEDIA_STORAGE_PATH must be inside %s\n' "$DATA_ROOT" >&2
    exit 1
fi
if [[ "$TLS_KEYSTORE_PATH" != "$CONFIG_ROOT/tls/"* ]]; then
    printf 'TLS_KEYSTORE_PATH must be inside %s/tls\n' "$CONFIG_ROOT" >&2
    exit 1
fi
if [[ "$SMTP_STARTTLS" != "true" && "$SMTP_SSL" != "true" ]]; then
    printf 'Production SMTP must enable STARTTLS or SSL\n' >&2
    exit 1
fi
pg_isready -h 127.0.0.1 -p 5432 -q

if ! id "$SERVICE_USER" >/dev/null 2>&1; then
    useradd --system --home-dir "$DATA_ROOT" --shell /usr/sbin/nologin "$SERVICE_USER"
fi
install -d -m 0750 -o root -g "$SERVICE_USER" "$CONFIG_ROOT" "$CONFIG_ROOT/tls"
install -d -m 0750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$DATA_ROOT" "$MEDIA_STORAGE_PATH"
chown root:"$SERVICE_USER" "$CONFIG_ROOT/server.env" "$CONFIG_ROOT/tls/server.p12"
chmod 0640 "$CONFIG_ROOT/server.env" "$CONFIG_ROOT/tls/server.p12"

ROLE_EXISTS=$(runuser -u postgres -- psql -tAqc "SELECT 1 FROM pg_roles WHERE rolname = '$DATABASE_USER'")
if [[ "$ROLE_EXISTS" == "1" ]]; then
    runuser -u postgres -- psql --quiet <<SQL
ALTER ROLE $DATABASE_USER WITH LOGIN PASSWORD '$DATABASE_PASSWORD';
SQL
else
    runuser -u postgres -- psql --quiet <<SQL
CREATE ROLE $DATABASE_USER LOGIN PASSWORD '$DATABASE_PASSWORD';
SQL
fi
DATABASE_NAME=${DATABASE_URL##*/}
DATABASE_NAME=${DATABASE_NAME%%\?*}
if [[ ! "$DATABASE_NAME" =~ ^[a-z_][a-z0-9_]*$ ]]; then
    printf 'Database name must be a simple PostgreSQL identifier\n' >&2
    exit 1
fi
DATABASE_EXISTS=$(runuser -u postgres -- psql -tAqc "SELECT 1 FROM pg_database WHERE datname = '$DATABASE_NAME'")
if [[ "$DATABASE_EXISTS" != "1" ]]; then
    runuser -u postgres -- psql --quiet -c "CREATE DATABASE $DATABASE_NAME OWNER $DATABASE_USER"
fi

install -m 0644 "$APP_ROOT/deploy/secure-messenger.service" /etc/systemd/system/secure-messenger.service
systemctl daemon-reload
systemctl enable secure-messenger.service
systemctl restart secure-messenger.service
systemctl --no-pager --full status secure-messenger.service
