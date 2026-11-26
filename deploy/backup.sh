#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    printf 'Run backups as root on the Linux host\n' >&2
    exit 1
fi
if [[ -z "${BACKUP_PASSWORD:-}" || ${#BACKUP_PASSWORD} -lt 32 ]]; then
    printf 'Set BACKUP_PASSWORD to a secret of at least 32 characters\n' >&2
    exit 1
fi
for command in gpg tar psql runuser systemctl; do
    command -v "$command" >/dev/null || {
        printf 'Required command is missing: %s\n' "$command" >&2
        exit 1
    }
done

DESTINATION=${1:-/var/backups/secure-messenger/$(date -u +%Y%m%dT%H%M%SZ).tar.gpg}
install -d -m 0700 "$(dirname "$DESTINATION")"
TEMP_DIR=$(mktemp -d /var/lib/secure-messenger/backup.XXXXXX)
WAS_ACTIVE=false
if systemctl is-active --quiet secure-messenger; then WAS_ACTIVE=true; fi
cleanup() {
    shred -u "$TEMP_DIR/database.sql" 2>/dev/null || true
    rmdir "$TEMP_DIR" 2>/dev/null || true
    if [[ "$WAS_ACTIVE" == "true" ]]; then systemctl start secure-messenger; fi
}
trap cleanup EXIT

if [[ "$WAS_ACTIVE" == "true" ]]; then systemctl stop secure-messenger; fi
runuser -u postgres -- pg_dump secure_messenger > "$TEMP_DIR/database.sql"
tar -cf - \
    -C "$TEMP_DIR" database.sql \
    -C /var/lib/secure-messenger media \
    -C /etc secure-messenger \
    | gpg --symmetric --cipher-algo AES256 --digest-algo SHA512 \
        --s2k-mode 3 --s2k-digest-algo SHA512 --s2k-count 65011712 \
        --no-symkey-cache --no-tty --batch --yes --pinentry-mode loopback \
        --passphrase-fd 3 --output "$DESTINATION" 3<<<"$BACKUP_PASSWORD"
chmod 0600 "$DESTINATION"
gpg --decrypt --no-tty --batch --yes --pinentry-mode loopback \
    --passphrase-fd 3 3<<<"$BACKUP_PASSWORD" "$DESTINATION" | tar -tf - >/dev/null
printf 'Encrypted and integrity-checked backup created: %s\n' "$DESTINATION"
