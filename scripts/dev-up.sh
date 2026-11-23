#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
bash scripts/generate-dev-tls.sh
mkdir -p .local

if [[ ! -f .local/dev-env.sh ]]; then
    {
        printf 'export POSTGRES_PASSWORD=%q\n' "$(openssl rand -hex 32)"
        printf 'export DATABASE_PASSWORD=%q\n' "$(openssl rand -hex 32)"
        printf 'export ADMIN_TOKEN=%q\n' "$(openssl rand -hex 32)"
        printf 'export TOKEN_PEPPER_BASE64=%q\n' "$(openssl rand -base64 32 | tr -d '\r\n')"
    } > .local/dev-env.sh
fi
source .local/dev-env.sh
TOKEN_PEPPER_BASE64="${TOKEN_PEPPER_BASE64//$'\r'/}"
export POSTGRES_PASSWORD DATABASE_PASSWORD ADMIN_TOKEN TOKEN_PEPPER_BASE64
export TLS_KEYSTORE_PATH="$(cygpath -m "$ROOT")/.local/tls/server.p12"
export TLS_KEYSTORE_PASSWORD="$(<.local/tls/keystore-password)"
export TLS_PRIVATE_KEY_PASSWORD="$TLS_KEYSTORE_PASSWORD"
export TLS_KEY_ALIAS="messenger"
export DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/secure_messenger"
export DATABASE_USER="messenger"
export SMTP_HOST="127.0.0.1"
export SMTP_PORT="1025"
export SMTP_FROM="messenger@localhost"
export SMTP_STARTTLS="false"
export SMTP_SSL="false"

MAILPIT_DIR="$ROOT/.local/mailpit"
mkdir -p "$MAILPIT_DIR"
if [[ ! -f "$MAILPIT_DIR/mailpit.exe" ]]; then
    curl -L --fail --max-time 120 -o "$MAILPIT_DIR/mailpit.zip" "https://github.com/axllent/mailpit/releases/download/v1.34.0/mailpit-windows-amd64.zip"
    unzip -q -o "$MAILPIT_DIR/mailpit.zip" -d "$MAILPIT_DIR"
fi
if ! curl -fsS --max-time 2 http://127.0.0.1:8025/readyz >/dev/null 2>&1; then
    nohup "$MAILPIT_DIR/mailpit.exe" --smtp 127.0.0.1:1025 --listen 127.0.0.1:8025 > "$MAILPIT_DIR/mailpit.log" 2>&1 &
    echo $! > "$MAILPIT_DIR/mailpit.pid"
fi
if ! (exec 3<>/dev/tcp/127.0.0.1/5432) 2>/dev/null; then
    nohup ./gradlew --no-daemon :devtools:run > "$ROOT/.local/postgres.log" 2>&1 &
    echo $! > "$ROOT/.local/postgres.pid"
fi
for _ in {1..60}; do
    if curl -fsS --max-time 2 http://127.0.0.1:8025/readyz >/dev/null 2>&1 && (exec 3<>/dev/tcp/127.0.0.1/5432) 2>/dev/null; then
        break
    fi
    sleep 1
done
if command -v adb >/dev/null 2>&1; then
    adb reverse tcp:8443 tcp:8443
elif [[ -x "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]]; then
    "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" reverse tcp:8443 tcp:8443
fi

export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
./gradlew --no-daemon :server:run
