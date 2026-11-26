#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
export PATH="$JAVA_HOME/bin:$PATH"
SIGNING_ENV="$ROOT/.local/release-signing.sh"
KEYSTORE="$ROOT/.local/release/securemessenger-release.p12"

if [[ ! -f "$SIGNING_ENV" ]]; then
    if [[ -f "$KEYSTORE" ]]; then
        printf 'Keystore exists but its signing environment is missing; restore .local/release-signing.sh from the secure backup\n' >&2
        exit 1
    fi
    mkdir -p "$ROOT/.local/release"
    PASSWORD="$(openssl rand -hex 32)"
    KEYTOOL="$JAVA_HOME/bin/keytool"
    if [[ -x "$JAVA_HOME/bin/keytool.exe" ]]; then KEYTOOL="$JAVA_HOME/bin/keytool.exe"; fi
    "$KEYTOOL" -genkeypair -storetype PKCS12 -keystore "$KEYSTORE" \
        -storepass "$PASSWORD" -keypass "$PASSWORD" -alias messenger \
        -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Private Messenger"
    {
        printf 'export MESSENGER_KEYSTORE_FILE=%q\n' "$(cygpath -m "$KEYSTORE")"
        printf 'export MESSENGER_KEYSTORE_PASSWORD=%q\n' "$PASSWORD"
        printf 'export MESSENGER_KEY_ALIAS=%q\n' 'messenger'
        printf 'export MESSENGER_KEY_PASSWORD=%q\n' "$PASSWORD"
    } > "$SIGNING_ENV"
    chmod 600 "$SIGNING_ENV" "$KEYSTORE"
    printf 'Created a new release key; back up .local/release and .local/release-signing.sh securely\n'
fi
source "$SIGNING_ENV"

if [[ "${ALLOW_LOCAL_RELEASE:-}" != "true" ]]; then
    if [[ ! "${API_BASE_URL:-}" =~ ^https:// ]] || [[ "${API_BASE_URL:-}" == *127.0.0.1* || "${API_BASE_URL:-}" == *localhost* ]]; then
        printf 'Set API_BASE_URL to the public HTTPS server endpoint, or ALLOW_LOCAL_RELEASE=true for a local signing check\n' >&2
        exit 1
    fi
    if [[ -n "${API_FALLBACK_URL:-}" && ! "${API_FALLBACK_URL}" =~ ^https:// ]]; then
        printf 'API_FALLBACK_URL must use HTTPS\n' >&2
        exit 1
    fi
fi

gradle_args=(assembleRelease :server:installDist)
if [[ -n "${API_BASE_URL:-}" ]]; then gradle_args+=(-PAPI_BASE_URL="$API_BASE_URL"); fi
if [[ -n "${API_FALLBACK_URL:-}" ]]; then gradle_args+=(-PAPI_FALLBACK_URL="$API_FALLBACK_URL"); fi
if [[ -n "${CLIENT_SUFFIX:-}" ]]; then gradle_args+=(-PCLIENT_SUFFIX="$CLIENT_SUFFIX"); fi
./gradlew "${gradle_args[@]}"

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
APKSIGNER_JAR="${APKSIGNER_JAR:-${LOCALAPPDATA:-/c/Users/aleks/AppData/Local}/Android/Sdk/build-tools/37.0.0/lib/apksigner.jar}"
if [[ ! -f "$APKSIGNER_JAR" ]]; then
    printf 'apksigner.jar was not found; set APKSIGNER_JAR to its path\n' >&2
    exit 1
fi
"$JAVA_HOME/bin/java" -jar "$APKSIGNER_JAR" verify --verbose --print-certs "$APK"
printf 'Signed APK: %s\nServer distribution: %s\n' "$APK" "$ROOT/server/build/install/server"
