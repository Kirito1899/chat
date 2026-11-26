#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    printf 'Run TLS conversion as root\n' >&2
    exit 1
fi
source /etc/secure-messenger/server.env
CERT_ROOT="/etc/letsencrypt/live/$PUBLIC_DOMAIN"
if [[ ! -f "$CERT_ROOT/fullchain.pem" || ! -f "$CERT_ROOT/privkey.pem" ]]; then
    printf 'Certificate or private key is missing for %s\n' "$PUBLIC_DOMAIN" >&2
    exit 1
fi
TLS_GROUP=root
if id messenger >/dev/null 2>&1; then TLS_GROUP=messenger; fi
install -d -m 0750 -o root -g "$TLS_GROUP" /etc/secure-messenger/tls
openssl pkcs12 -export -name "$TLS_KEY_ALIAS" \
    -inkey "$CERT_ROOT/privkey.pem" -in "$CERT_ROOT/fullchain.pem" \
    -out /etc/secure-messenger/tls/server.p12 -passout env:TLS_KEYSTORE_PASSWORD
chown root:"$TLS_GROUP" /etc/secure-messenger/tls/server.p12
if [[ "$TLS_GROUP" == "messenger" ]]; then
    chmod 0640 /etc/secure-messenger/tls/server.p12
else
    chmod 0600 /etc/secure-messenger/tls/server.p12
fi
if systemctl cat secure-messenger.service >/dev/null 2>&1; then
    systemctl restart secure-messenger
fi
