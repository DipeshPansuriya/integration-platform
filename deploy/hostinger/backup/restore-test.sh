#!/bin/sh
# Restore test (ADR Decision 3, conformance C4): restore the newest ipass dump into a scratch database, compare the
# row counts of tenant and audit_event with the live database, drop the scratch database. Run before go-live and monthly:
#   docker compose exec pg-backup sh /scripts/restore-test.sh [dump-file]
# DRY_RUN=1 prints the steps only. Exit 0 = counts equal; exit 1 = they differ or a step failed.
# Counts are taken as ipass_backup (BYPASSRLS) - an ordinary login would see zero rows of the FORCE-RLS tables.
set -eu
BACKUP_DIR="${BACKUP_DIR:-/backups}"
DB="${POSTGRES_DB:-ipass}"
SCRATCH=ipass_restore_check
USER_="${BACKUP_DB_USERNAME:-ipass_backup}"
HOST="${PGHOST:-postgres}"
run() { if [ "${DRY_RUN:-0}" = 1 ]; then echo "$@"; else "$@"; fi; }

file="${1:-}"
if [ -z "$file" ]; then file="$(ls -1t "$BACKUP_DIR"/"$DB"-*.dump 2>/dev/null | head -1 || true)"; fi
if [ -z "$file" ] && [ "${DRY_RUN:-0}" != 1 ]; then echo "no $DB dump in $BACKUP_DIR" >&2; exit 1; fi
file="${file:-$BACKUP_DIR/$DB-<newest>.dump}"
echo "restoring $file into $SCRATCH"

run dropdb -h "$HOST" -U "$USER_" --if-exists "$SCRATCH"
run createdb -h "$HOST" -U "$USER_" "$SCRATCH"
run pg_restore -h "$HOST" -U "$USER_" --no-owner --no-acl -d "$SCRATCH" "$file"
if [ "${DRY_RUN:-0}" = 1 ]; then echo "compare count(*) of tenant and audit_event in $DB and $SCRATCH"; echo "dropdb $SCRATCH"; exit 0; fi

count() { psql -h "$HOST" -U "$USER_" -d "$1" -Atc "SELECT count(*) FROM public.$2"; }
status=0
for t in tenant audit_event; do
  a="$(count "$DB" "$t")"; b="$(count "$SCRATCH" "$t")"
  if [ "$a" = "$b" ]; then echo "OK   $t live=$a restored=$b"; else echo "DIFF $t live=$a restored=$b"; status=1; fi
done
dropdb -h "$HOST" -U "$USER_" --if-exists "$SCRATCH"
exit "$status"
