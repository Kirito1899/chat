#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :server:installDist
PACKAGE_DIR=$(mktemp -d "$ROOT/build/server-package.XXXXXX")
mkdir -p "$PACKAGE_DIR/secure-messenger"
cp -R "$ROOT/server/build/install/server/." "$PACKAGE_DIR/secure-messenger/"
cp -R "$ROOT/deploy" "$PACKAGE_DIR/secure-messenger/deploy"
ARCHIVE="$ROOT/build/secure-messenger-server.tar.gz"
tar -czf "$ARCHIVE" -C "$PACKAGE_DIR" secure-messenger
sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"
printf 'Server package: %s\n' "$ARCHIVE"
