#!/bin/sh
# pg-backup: nightly `pg_dump -Fc` of the iPass and Keycloak databases at 02:00 UTC into /backups, keeping 7 daily and
# 4 weekly dumps; logs the dump size to stdout (ADR-H-IPASS-01 Decision 3). Runs as a loop in the pg-backup container.
#   RUN_ONCE=1   dump now, prune, exit (manual dump before any change: docker compose exec pg-backup env RUN_ONCE=1 sh /scripts/backup.sh)
#   DRY_RUN=1    print the pg_dump commands instead of running them
#   BACKUP_SOURCE_ONLY=1   define the functions only (tests)
# Both databases are dumped as ipass_backup (BYPASSRLS + pg_read_all_data: the audit tables FORCE RLS) - the one
# credential this container holds (PGPASSWORD). A dump counts only when pg_dump succeeded AND pg_restore can list it; a
# failed or unreadable dump is deleted, the run exits non-zero, and nothing is pruned, so the last good dump survives.
set -eu
umask 077     # dumps hold the audit trail: owner-only
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

dump_one() { # database, user, stamp
  name="$1"; user="$2"; stamp="$3"
  file="$BACKUP_DIR/$name-$stamp.dump"
  if [ "${DRY_RUN:-0}" = 1 ]; then echo "pg_dump -Fc -h ${PGHOST:-postgres} -U $user -f $file $name"; return 0; fi
  if ! pg_dump -Fc -h "${PGHOST:-postgres}" -U "$user" -f "$file.part" "$name"; then
    rm -f "$file.part"; echo "backup FAILED $name: pg_dump error" >&2; return 1
  fi
  if ! pg_restore -l "$file.part" >/dev/null 2>&1; then
    rm -f "$file.part"; echo "backup FAILED $name: dump is not a readable archive" >&2; return 1
  fi
  mv "$file.part" "$file"
  echo "backup $name $(date -u +%FT%TZ) $(wc -c < "$file") bytes $file"
  # Sunday's dump is also kept as the weekly one
  if [ "$(date -u +%u)" = 7 ]; then mkdir -p "$BACKUP_DIR/weekly"; cp "$file" "$BACKUP_DIR/weekly/$name-$stamp.dump.part" && mv "$BACKUP_DIR/weekly/$name-$stamp.dump.part" "$BACKUP_DIR/weekly/$name-$stamp.dump"; fi
  return 0
}

prune() {
  [ "${DRY_RUN:-0}" = 1 ] && { echo "prune $BACKUP_DIR"; return 0; }
  find "$BACKUP_DIR" -maxdepth 1 -name '*.dump' -type f -mtime +"$DAILY_KEEP_DAYS" -exec rm -f {} +
  [ -d "$BACKUP_DIR/weekly" ] && find "$BACKUP_DIR/weekly" -name '*.dump' -type f -mtime +"$WEEKLY_KEEP_DAYS" -exec rm -f {} +
  return 0
}

backup_all() {
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  failed=0
  [ "${DRY_RUN:-0}" = 1 ] || mkdir -p "$BACKUP_DIR"
  user="${BACKUP_DB_USERNAME:-ipass_backup}"
  dump_one "$IPASS_DB" "$user" "$stamp" || failed=1
  dump_one "$KC_DB" "$user" "$stamp" || failed=1
  [ "$failed" -eq 0 ] && prune
  return "$failed"
}

if [ "${BACKUP_SOURCE_ONLY:-0}" != 1 ]; then
  if [ "${RUN_ONCE:-0}" = 1 ]; then backup_all; exit $?; fi
  while true; do
    s="$(( $(date -u +%s) % 86400 ))"
    w="$(seconds_until_run "$s")"
    echo "next backup in ${w}s"
    sleep "$w"
    backup_all || echo "backup run FAILED $(date -u +%FT%TZ)" >&2
  done
fi
