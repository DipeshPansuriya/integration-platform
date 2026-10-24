#!/bin/sh
# pg-backup: nightly `pg_dump -Fc` of the iPass and Keycloak databases at 02:00 UTC into /backups, keeping 7 daily and
# 4 weekly dumps; logs the dump size to stdout (ADR-H-IPASS-01 Decision 3). Runs as a loop in the pg-backup container.
#   RUN_ONCE=1   dump now, prune, exit (manual dump before any change: docker compose exec pg-backup env RUN_ONCE=1 sh /scripts/backup.sh)
#   DRY_RUN=1    print the pg_dump commands instead of running them
#   BACKUP_SOURCE_ONLY=1   define the functions only (tests)
# ipass is dumped as ipass_backup (BYPASSRLS + pg_read_all_data: the audit tables FORCE RLS); Keycloak as its own login.
set -eu
BACKUP_DIR="${BACKUP_DIR:-/backups}"
IPASS_DB="${POSTGRES_DB:-ipass}"
KC_DB="${KEYCLOAK_DB_NAME:-keycloak}"
DAILY_KEEP_DAYS="${DAILY_KEEP_DAYS:-7}"
WEEKLY_KEEP_DAYS="${WEEKLY_KEEP_DAYS:-28}"

# seconds until the next 02:00 UTC from a given seconds-since-midnight (never 0: a full day when it is 02:00:00 sharp)
seconds_until_run() {
  now="$1"
  wait=$(( (7200 - now + 86400) % 86400 ))
  [ "$wait" -eq 0 ] && wait=86400
  echo "$wait"
}

run() { if [ "${DRY_RUN:-0}" = 1 ]; then echo "$@"; else "$@"; fi; }

dump_one() { # name, user, password-or-empty (empty = keep PGPASSWORD)
  name="$1"; user="$2"; pw="$3"; stamp="$4"
  file="$BACKUP_DIR/$name-$stamp.dump"
  if [ "${DRY_RUN:-0}" = 1 ]; then echo "pg_dump -Fc -h ${PGHOST:-postgres} -U $user -f $file $name"; return 0; fi
  if [ -n "$pw" ]; then PGPASSWORD="$pw" pg_dump -Fc -h "${PGHOST:-postgres}" -U "$user" -f "$file.part" "$name"
  else pg_dump -Fc -h "${PGHOST:-postgres}" -U "$user" -f "$file.part" "$name"; fi
  mv "$file.part" "$file"
  echo "backup $name $(date -u +%FT%TZ) $(wc -c < "$file") bytes $file"
  # Sunday's dump is also kept as the weekly one
  if [ "$(date -u +%u)" = 7 ]; then mkdir -p "$BACKUP_DIR/weekly"; cp "$file" "$BACKUP_DIR/weekly/"; fi
}

prune() {
  [ "${DRY_RUN:-0}" = 1 ] && { echo "prune $BACKUP_DIR"; return 0; }
  find "$BACKUP_DIR" -maxdepth 1 -name '*.dump' -type f -mtime +"$DAILY_KEEP_DAYS" -exec rm -f {} +
  [ -d "$BACKUP_DIR/weekly" ] && find "$BACKUP_DIR/weekly" -name '*.dump' -type f -mtime +"$WEEKLY_KEEP_DAYS" -exec rm -f {} +
  return 0
}

backup_all() {
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  [ "${DRY_RUN:-0}" = 1 ] || mkdir -p "$BACKUP_DIR"
  dump_one "$IPASS_DB" "${BACKUP_DB_USERNAME:-ipass_backup}" "" "$stamp"
  dump_one "$KC_DB" "${KEYCLOAK_DB_USERNAME:-keycloak}" "${KEYCLOAK_DB_PASSWORD:-}" "$stamp"
  prune
}

if [ "${BACKUP_SOURCE_ONLY:-0}" != 1 ]; then
  if [ "${RUN_ONCE:-0}" = 1 ]; then backup_all; exit 0; fi
  while true; do
    s="$(( $(date -u +%s) % 86400 ))"
    w="$(seconds_until_run "$s")"
    echo "next backup in ${w}s"
    sleep "$w"
    backup_all || echo "backup FAILED $(date -u +%FT%TZ)" >&2
  done
fi
