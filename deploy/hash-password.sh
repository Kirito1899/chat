#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    printf 'Usage: hash-password.sh <password>\n' >&2
    exit 1
fi
WORK_DIR=$(mktemp -d /tmp/messenger-hash.XXXXXX)
cleanup() {
    shred -u "$WORK_DIR/HashPassword.java" "$WORK_DIR/HashPassword.class" 2>/dev/null || true
    rmdir "$WORK_DIR" 2>/dev/null || true
}
trap cleanup EXIT
cat > "$WORK_DIR/HashPassword.java" <<'JAVA'
public final class HashPassword {
    public static void main(String[] args) {
        System.out.println(new dev.securemessenger.server.security.Argon2PasswordHasher().hash(args[0]));
    }
}
JAVA
javac -cp '/opt/secure-messenger/lib/*' "$WORK_DIR/HashPassword.java"
java -cp "$WORK_DIR:/opt/secure-messenger/lib/*" HashPassword "$1"
