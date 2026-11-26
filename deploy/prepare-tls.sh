#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    printf 'Run TLS preparation as root on the Linux host\n' >&2
    exit 1
fi
source /opt/secure-messenger/deploy/load-env.sh
load_env /etc/secure-messenger/server.env
if [[ ! "${PUBLIC_DOMAIN:-}" =~ ^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
    printf 'Set PUBLIC_DOMAIN in server.env\n' >&2
    exit 1
fi
if [[ -z "${TLS_KEYSTORE_PASSWORD:-}" ]]; then
    printf 'TLS_KEYSTORE_PASSWORD is required\n' >&2
    exit 1
fi
command -v certbot >/dev/null || {
    printf 'certbot is required\n' >&2
    exit 1
}
if [[ "${LETSENCRYPT_AGREE_TOS:-}" != "true" || -z "${LETSENCRYPT_EMAIL:-}" ]]; then
    printf 'Set LETSENCRYPT_EMAIL and LETSENCRYPT_AGREE_TOS=true after reviewing the subscriber agreement\n' >&2
    exit 1
fi
if [[ ! -f "/etc/letsencrypt/live/$PUBLIC_DOMAIN/fullchain.pem" ]]; then
    certbot certonly --standalone --preferred-challenges http \
        --domain "$PUBLIC_DOMAIN" --email "$LETSENCRYPT_EMAIL" --agree-tos --non-interactive
fi
/opt/secure-messenger/deploy/convert-tls.sh
install -d -m 0755 /etc/letsencrypt/renewal-hooks/deploy
install -m 0700 /opt/secure-messenger/deploy/convert-tls.sh /etc/letsencrypt/renewal-hooks/deploy/secure-messenger.sh
