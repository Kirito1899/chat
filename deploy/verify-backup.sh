#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    printf 'Run backup verification as root on the Linux host\n' >&2
    exit 1
fi
if [[ -z "${BACKUP_PASSWORD:-}" || ${#BACKUP_PASSWORD} -lt 32 ]]; then
    printf 'Set BACKUP_PASSWORD to the backup secret\n' >&2
    exit 1
fi
ARCHIVE=${1:?Usage: verify-backup.sh /var/backups/secure-messenger/archive.tar.gpg}
if [[ ! -f "$ARCHIVE" ]]; then
    printf 'Backup archive not found: %s\n' "$ARCHIVE" >&2
    exit 1
fi
for command in gpg tar psql runuser createdb dropdb cmp diff shred; do
    command -v "$command" >/dev/null || {
        printf 'Required command is missing: %s\n' "$command" >&2
        exit 1
    }
done

RESTORE_DB="secure_messenger_restore_$(date -u +%Y%m%d%H%M%S)"
DATABASE_CREATED=false
TEMP_DIR=$(mktemp -d /var/lib/secure-messenger/restore.XXXXXX)
cleanup() {
    if [[ "$DATABASE_CREATED" == "true" ]]; then
        runuser -u postgres -- dropdb --if-exists "$RESTORE_DB" || \
            printf 'Temporary database may remain and requires manual review: %s\n' "$RESTORE_DB" >&2
    fi
    shred -u "$TEMP_DIR/database.sql" "$TEMP_DIR/source-counts" "$TEMP_DIR/restored-counts" 2>/dev/null || true
    rmdir "$TEMP_DIR" 2>/dev/null || true
}
trap cleanup EXIT

gpg --decrypt --no-tty --batch --yes --pinentry-mode loopback \
    --passphrase-fd 3 3<<<"$BACKUP_PASSWORD" "$ARCHIVE" \
    | tar -xOf - database.sql > "$TEMP_DIR/database.sql"
runuser -u postgres -- createdb --owner=messenger "$RESTORE_DB"
DATABASE_CREATED=true
runuser -u postgres -- psql --no-psqlrc --set=ON_ERROR_STOP=1 --quiet \
    --dbname="$RESTORE_DB" < "$TEMP_DIR/database.sql"

table_counts() {
    runuser -u postgres -- psql --no-psqlrc --set=ON_ERROR_STOP=1 --tuples-only --no-align \
        --dbname="$1" <<'SQL'
SELECT format('SELECT %L, count(*) FROM %I.%I', tablename, schemaname, tablename)
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY tablename \gexec
SQL
}
table_counts secure_messenger > "$TEMP_DIR/source-counts"
table_counts "$RESTORE_DB" > "$TEMP_DIR/restored-counts"
if ! cmp -s "$TEMP_DIR/source-counts" "$TEMP_DIR/restored-counts"; then
    printf 'Restored table counts differ; production may have changed after the backup\n' >&2
    diff -u "$TEMP_DIR/source-counts" "$TEMP_DIR/restored-counts" >&2 || true
    exit 1
fi
runuser -u postgres -- dropdb "$RESTORE_DB"
DATABASE_CREATED=false
printf 'Backup decrypted and restored successfully; schema table counts match. Temporary database removed: %s\n' "$RESTORE_DB"
