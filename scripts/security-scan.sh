#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
export PATH="$JAVA_HOME/bin:$PATH"
CHECK="$ROOT/.local/security-tools/dependency-check-12.1.9/dependency-check/bin/dependency-check.sh"
EXPECTED_SHA256="ea7ae841ae9005d5d6e957994dec996fd9787279ead355c582a226c67ff6aa37"
ARCHIVE="$ROOT/.local/security-tools/dependency-check-12.1.9-release.zip"

if [[ ! -f "$ARCHIVE" ]]; then
    printf 'Download OWASP Dependency-Check 12.1.9 into .local/security-tools; expected SHA-256: %s\n' "$EXPECTED_SHA256" >&2
    printf 'https://github.com/dependency-check/DependencyCheck/releases/download/v12.1.9/dependency-check-12.1.9-release.zip\n' >&2
    exit 1
fi
ACTUAL_SHA256=$(sha256sum "$ARCHIVE" | awk '{print $1}')
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    printf 'Dependency-Check archive SHA-256 mismatch\n' >&2
    exit 1
fi
unzip -q -o "$ARCHIVE" -d "$ROOT/.local/security-tools/dependency-check-12.1.9"
if [[ ! -x "$CHECK" ]]; then
    printf 'Dependency-Check launcher is missing or not executable: %s\n' "$CHECK" >&2
    exit 1
fi

./gradlew assembleDebug :server:installDist
mkdir -p build/security .local/dependency-check-data
args=(
    --project "Private Messenger"
    --scan "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    --scan "$ROOT/server/build/install/server/lib"
    --data "$ROOT/.local/dependency-check-data"
    --out "$ROOT/build/security"
    --format HTML
    --format JSON
    --failOnCVSS 7
    --log "$ROOT/build/security/dependency-check.log"
)
if [[ -n "${NVD_API_KEY:-}" ]]; then
    args+=(--nvdApiKey "$NVD_API_KEY")
fi
"$CHECK" "${args[@]}"
