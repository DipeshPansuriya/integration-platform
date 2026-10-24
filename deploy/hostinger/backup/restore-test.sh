#!/bin/sh
# Restore test (ADR Decision 3, conformance C4): restore the newest ipass dump into a scratch database, compare the
# row counts of tenant and audit_event with the live database, drop the scratch database. Run before go-live and monthly:
#   docker compose --profile restore-test run --rm pg-restore-test [dump-file]
# DRY_RUN=1 prints the steps only. Exit 0 = counts equal; exit 1 = they differ or a step failed.
# Two logins, each with only what it needs: ipass_restore (CREATEDB, no BYPASSRLS) creates, fills and drops the scratch
# database; ipass_backup (BYPASSRLS + pg_read_all_data) only counts rows - an ordinary login sees zero rows of the
# FORCE-RLS tables. Their passwords arrive as RESTORE_DB_PASSWORD / BACKUP_DB_PASSWORD and reach each command through
# its own PGPASSWORD; this one-shot container exists only while the test runs.
set -eu
BACKUP_DIR="${BACKUP_DIR:-/backups}"
DB="${POSTGRES_DB:-ipass}"
SCRATCH=ipass_restore_check
RUSER="${RESTORE_DB_USERNAME:-ipass_restore}"
BUSER="${BACKUP_DB_USERNAME:-ipass_backup}"
HOST="${PGHOST:-postgres}"
run() { if [ "${DRY_RUN:-0}" = 1 ]; then echo "$@"; else PGPASSWORD="${RESTORE_DB_PASSWORD:-}" "$@"; fi; }

file="${1:-}"
if [ -z "$file" ]; then file="$(ls -1t "$BACKUP_DIR"/"$DB"-*.dump 2>/dev/null | head -1 || true)"; fi
if [ -z "$file" ] && [ "${DRY_RUN:-0}" != 1 ]; then echo "no $DB dump in $BACKUP_DIR" >&2; exit 1; fi
file="${file:-$BACKUP_DIR/$DB-<newest>.dump}"
echo "restoring $file into $SCRATCH"

run dropdb -h "$HOST" -U "$RUSER" --if-exists "$SCRATCH"
run createdb -h "$HOST" -U "$RUSER" "$SCRATCH"
if [ "${DRY_RUN:-0}" != 1 ]; then trap 'PGPASSWORD="${RESTORE_DB_PASSWORD:-}" dropdb -h "$HOST" -U "$RUSER" --if-exists "$SCRATCH" >/dev/null 2>&1' EXIT; fi
run pg_restore -h "$HOST" -U "$RUSER" --no-owner --no-acl -d "$SCRATCH" "$file"
if [ "${DRY_RUN:-0}" = 1 ]; then
  echo "count(*) of tenant and audit_event in $DB and $SCRATCH as $BUSER"
  echo "dropdb -h $HOST -U $RUSER --if-exists $SCRATCH"
  exit 0
fi

count() { PGPASSWORD="${BACKUP_DB_PASSWORD:-}" psql -h "$HOST" -U "$BUSER" -d "$1" -Atc "SELECT count(*) FROM public.$2"; }
status=0
for t in tenant audit_event; do
  a="$(count "$DB" "$t")"; b="$(count "$SCRATCH" "$t")"
  if [ "$a" = "$b" ]; then echo "OK   $t live=$a restored=$b"; else echo "DIFF $t live=$a restored=$b"; status=1; fi
done
run dropdb -h "$HOST" -U "$RUSER" --if-exists "$SCRATCH"
exit "$status"
