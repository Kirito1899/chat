#!/usr/bin/env bash
set -euo pipefail

source /opt/secure-messenger/deploy/load-env.sh
load_env /etc/secure-messenger/server.env
USERNAME="$1"
PASSWORD="$2"
EMAIL="${LETSENCRYPT_EMAIL%@*}+${USERNAME}@${LETSENCRYPT_EMAIL#*@}"
HASH=$(runuser -u postgres -- psql -tAqc "SELECT password_hash FROM accounts WHERE email = '$EMAIL'" secure_messenger)
if [[ -z "$HASH" ]]; then
    printf 'Test account is missing\n' >&2
    exit 1
fi
WORK_DIR=$(mktemp -d /tmp/messenger-verify.XXXXXX)
cleanup() {
    shred -u "$WORK_DIR/VerifyPassword.java" "$WORK_DIR/VerifyPassword.class" 2>/dev/null || true
    rmdir "$WORK_DIR" 2>/dev/null || true
}
trap cleanup EXIT
cat > "$WORK_DIR/VerifyPassword.java" <<'JAVA'
public final class VerifyPassword {
    public static void main(String[] args) {
        System.out.println(new dev.securemessenger.server.security.Argon2PasswordHasher().verify(args[0], args[1]));
    }
}
JAVA
javac -cp '/opt/secure-messenger/lib/*' "$WORK_DIR/VerifyPassword.java"
java -cp "$WORK_DIR:/opt/secure-messenger/lib/*" VerifyPassword "$PASSWORD" "$HASH"
