#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    printf 'Run environment creation as root on the Linux host\n' >&2
    exit 1
fi
CONFIG=/etc/secure-messenger/server.env
if [[ -f "$CONFIG" && "${REPLACE_PRODUCTION_CONFIG:-}" != "true" ]]; then
    printf '%s already exists; back it up and set REPLACE_PRODUCTION_CONFIG=true to replace it\n' "$CONFIG" >&2
    exit 1
fi
source /opt/secure-messenger/deploy/load-env.sh
if [[ -f /etc/secure-messenger/smtp.env ]]; then
    load_env /etc/secure-messenger/smtp.env
fi
if [[ ! "${PUBLIC_DOMAIN:-}" =~ ^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
    printf 'Set PUBLIC_DOMAIN to the server DNS name\n' >&2
    exit 1
fi
if [[ -z "${SMTP_HOST:-}" || -z "${SMTP_FROM:-}" ]]; then
    printf 'Set SMTP_HOST and SMTP_FROM in smtp.env\n' >&2
    exit 1
fi
if [[ ! "${SMTP_PORT:-587}" =~ ^[0-9]+$ || "${SMTP_PORT:-587}" -lt 1 || "${SMTP_PORT:-587}" -gt 65535 ]]; then
    printf 'SMTP_PORT must be between 1 and 65535\n' >&2
    exit 1
fi
for variable in PUBLIC_DOMAIN LETSENCRYPT_EMAIL SMTP_HOST SMTP_PORT SMTP_USERNAME SMTP_PASSWORD SMTP_FROM; do
    if [[ "${!variable:-}" == *$'\n'* || "${!variable:-}" == *$'\r'* ]]; then
        printf '%s must not contain a newline\n' "$variable" >&2
        exit 1
    fi
done

write_assignment() {
    local key="$1" value="$2"
    value="${value//\\/\\\\}"
    value="${value//\"/\\\"}"
    printf '%s="%s"\n' "$key" "$value"
}

umask 077
install -d -m 0700 /etc/secure-messenger
TEMP_CONFIG=$(mktemp /etc/secure-messenger/server.env.XXXXXX)
{
    write_assignment KTOR_HOST 0.0.0.0
    write_assignment KTOR_SSL_PORT 8443
    write_assignment JAVA_OPTS "-Xms64m -Xmx256m -XX:+UseG1GC"
    write_assignment PUBLIC_DOMAIN "$PUBLIC_DOMAIN"
    write_assignment LETSENCRYPT_EMAIL "${LETSENCRYPT_EMAIL:-$SMTP_FROM}"
    write_assignment LETSENCRYPT_AGREE_TOS "${LETSENCRYPT_AGREE_TOS:-false}"
    write_assignment DATABASE_URL jdbc:postgresql://127.0.0.1:5432/secure_messenger
    write_assignment DATABASE_USER messenger
    write_assignment DATABASE_PASSWORD "$(openssl rand -hex 32)"
    write_assignment POSTGRES_PASSWORD "$(openssl rand -hex 32)"
    write_assignment ADMIN_TOKEN "$(openssl rand -hex 32)"
    write_assignment TOKEN_PEPPER_BASE64 "$(openssl rand -base64 32 | tr -d '\r\n')"
    TLS_PASSWORD=$(openssl rand -hex 32)
    write_assignment MEDIA_STORAGE_PATH /var/lib/secure-messenger/media
    write_assignment TLS_KEYSTORE_PATH /etc/secure-messenger/tls/server.p12
    write_assignment TLS_KEY_ALIAS messenger
    write_assignment TLS_KEYSTORE_PASSWORD "$TLS_PASSWORD"
    write_assignment TLS_PRIVATE_KEY_PASSWORD "$TLS_PASSWORD"
    write_assignment SMTP_HOST "$SMTP_HOST"
    write_assignment SMTP_PORT "${SMTP_PORT:-587}"
    write_assignment SMTP_USERNAME "${SMTP_USERNAME:-}"
    write_assignment SMTP_PASSWORD "${SMTP_PASSWORD:-}"
    write_assignment SMTP_FROM "$SMTP_FROM"
    write_assignment SMTP_STARTTLS "${SMTP_STARTTLS:-true}"
    write_assignment SMTP_SSL "${SMTP_SSL:-false}"
} > "$TEMP_CONFIG"
mv "$TEMP_CONFIG" "$CONFIG"
printf 'Created %s with generated secrets; keep it root-only\n' "$CONFIG"
