#!/usr/bin/env bash
set -euo pipefail
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TLS_DIR="$(cygpath -m "$ROOT")/.local/tls"
DEBUG_RAW="$ROOT/app/src/debug/res/raw"
mkdir -p "$TLS_DIR" "$DEBUG_RAW"

if [[ ! -f "$TLS_DIR/ca.key" || ! -f "$TLS_DIR/ca.crt" ]]; then
    openssl req -x509 -newkey rsa:3072 -nodes -days 30 \
        -keyout "$TLS_DIR/ca.key" -out "$TLS_DIR/ca.crt" \
        -subj "/CN=Secure Messenger Development CA" \
        -addext "basicConstraints=critical,CA:TRUE" \
        -addext "keyUsage=critical,keyCertSign,cRLSign"
fi

if [[ ! -f "$TLS_DIR/keystore-password" ]]; then
    openssl rand -hex 32 > "$TLS_DIR/keystore-password"
fi
PASSWORD="$(<"$TLS_DIR/keystore-password")"

cat > "$TLS_DIR/server-extensions.cnf" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=IP:127.0.0.1,DNS:localhost
EOF

openssl req -newkey rsa:3072 -nodes \
    -keyout "$TLS_DIR/server.key" -out "$TLS_DIR/server.csr" \
    -subj "/CN=localhost"
openssl x509 -req -days 14 \
    -in "$TLS_DIR/server.csr" -CA "$TLS_DIR/ca.crt" -CAkey "$TLS_DIR/ca.key" \
    -CAcreateserial -out "$TLS_DIR/server.crt" \
    -extfile "$TLS_DIR/server-extensions.cnf"
openssl pkcs12 -export \
    -inkey "$TLS_DIR/server.key" -in "$TLS_DIR/server.crt" -certfile "$TLS_DIR/ca.crt" \
    -name messenger -out "$TLS_DIR/server.p12" -passout "pass:$PASSWORD"
cp "$TLS_DIR/ca.crt" "$DEBUG_RAW/dev_ca.crt"
chmod 600 "$TLS_DIR/ca.key" "$TLS_DIR/server.key" "$TLS_DIR/server.p12" "$TLS_DIR/keystore-password" 2>/dev/null || true
