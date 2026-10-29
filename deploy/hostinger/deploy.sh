#!/bin/sh
# iPass release on the Hostinger VPS: backup -> Flyway info/validate/migrate -> switch the image tag -> start -> health.
# Run in the stack folder (/docker/ipass), as the user that runs docker:
#   IMAGE_TAG=<short commit of the merged main> ./deploy.sh
# Optional: COMPOSE_FILE=compose.yaml:demo/compose.demo.yaml (docker compose's own variable) when the demo overlay is in use;
#           HEALTH_SERVICES (default "postgres keycloak platform-app edge"), HEALTH_TIMEOUT (seconds, default 300).
# The Flyway image <prefix>/flyway-migrate:<tag> must exist locally or be pullable (build it on the build PC, README "Migration
# pipeline"; or `docker save ... | ssh <vps> docker load`). Nothing changes (tag, containers) before the migration has succeeded.
# Since row 570 the hosted app does NOT run Flyway (compose.yaml: SPRING_FLYWAY_ENABLED=false, no owner login in its environment); this script is the only migration path.
# EXPAND/CONTRACT RULE: the old app keeps serving while the migration runs (steps 3-4, before `up -d`), so every migration of a
# release must work with the PREVIOUS app version too (add columns/tables first, drop things in a later release).
# No explicit `compose pull`: `up -d` pulls what is missing (pull_policy: missing / digest pins), and images brought in with
# `docker save | docker load` exist only locally, where a pull would fail.
# Exit: 0 released and healthy, 2 bad input/setup, 3 backup failed, 4 Flyway image missing, 5 migration (or validate) failed,
#       6 started but a service is not healthy (roll back with the printed steps).
# Backups: release-backups/ (mode 700) keeps .env.bak and compose.yaml.bak (mode 600) per release; only the newest RELEASE_BACKUPS_KEEP
# (default 5) timestamp folders are kept, older ones are removed and logged; the current release's folder is never removed.
# The env file is read for IMAGE_TAG only by name and rewritten line-wise; it is never sourced or eval'd, and no secret is printed.
set -eu
umask 077       # backups of the env file hold secrets

DOCKER="${DOCKER:-docker}"
STACK_DIR="${STACK_DIR:-$(cd "$(dirname "$0")" && pwd)}"
cd "$STACK_DIR"
ENV_FILE="${ENV_FILE:-$STACK_DIR/.env}"
export ENV_FILE
LOCK_DIR="$STACK_DIR/.deploy.lock"
tmp=""
cleanup_deploy() { [ -z "$tmp" ] || rm -f "$tmp"; if [ "$HAVE_LOCK" = 1 ]; then rm -f "$LOCK_DIR/pid"; rmdir "$LOCK_DIR" 2>/dev/null || true; fi; }
HAVE_LOCK=0
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  old="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
  if [ -n "$old" ] && ! kill -0 "$old" 2>/dev/null; then
    echo "NOTE: stale deploy lock (process $old is gone): taking it over"; rm -f "$LOCK_DIR/pid"; rmdir "$LOCK_DIR" 2>/dev/null; mkdir "$LOCK_DIR" 2>/dev/null || old=""
    [ -n "$old" ] || { echo "ERROR: cannot take over the stale lock $LOCK_DIR" >&2; exit 2; }
  else echo "ERROR: another deploy.sh is running (or died without cleaning up): $LOCK_DIR exists. Check with ps; remove the folder only if no deploy is running." >&2; exit 2
  fi
fi
HAVE_LOCK=1; echo $$ > "$LOCK_DIR/pid"
trap cleanup_deploy EXIT
trap 'cleanup_deploy; exit 130' INT TERM
[ -r "$ENV_FILE" ] || { echo "ERROR: cannot read $ENV_FILE" >&2; exit 2; }

NEW_TAG="${IMAGE_TAG:-}"
[ -n "$NEW_TAG" ] || { echo "ERROR: IMAGE_TAG must be set to the short commit of the merged main, e.g.  IMAGE_TAG=ab12cd34 ./deploy.sh" >&2; exit 2; }
case "$NEW_TAG" in
  *[!0-9a-f]*|?|??|???|????|?????|??????) echo "ERROR: IMAGE_TAG must be the first 7 or more lowercase hex characters of the commit (as flyway/build-image.sh requires)" >&2; exit 2 ;;
  latest) echo "ERROR: IMAGE_TAG must not be 'latest'" >&2; exit 2 ;;
  *[!A-Za-z0-9._-]*|-*|.*) echo "ERROR: IMAGE_TAG may contain only letters, digits, '.', '_' and '-'" >&2; exit 2 ;;
esac
# the tag currently recorded in the env file (kept separately: IMAGE_TAG itself is the NEW tag from here on)
TAG_LINE='^[[:space:]]*(export[[:space:]]+)?IMAGE_TAG='      # the ONE pattern for reading and rewriting the tag line
OLD_TAG="$(grep -E "$TAG_LINE" "$ENV_FILE" | tail -n 1 | tr -d '\r' | cut -d= -f2- | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\\(.*\\)'\$/\\1/")" || true
OLD_TAG="${OLD_TAG:-unknown}"
KEEP_BACKUPS="${RELEASE_BACKUPS_KEEP:-5}"
case "$KEEP_BACKUPS" in ''|*[!0-9]*|0) echo "ERROR: RELEASE_BACKUPS_KEEP must be a whole number of 1 or more (default 5)" >&2; exit 2 ;; esac
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="$STACK_DIR/release-backups/$STAMP"
export IMAGE_TAG="$NEW_TAG"      # compose and migrate.sh both read it from the environment first

echo "=== iPass release $STAMP: $OLD_TAG -> $NEW_TAG ==="

rollback_note() {
  echo "To go back to $OLD_TAG:" >&2
  echo "  1. cp -p $BACKUP_DIR/.env.bak $ENV_FILE ; cp -p $BACKUP_DIR/compose.yaml.bak compose.yaml" >&2
  echo "  2. if a migration of this release already ran and the schema must go back: restore the pre-release dump (README, Migration pipeline > Restore)" >&2
  echo "  3. IMAGE_TAG=$OLD_TAG $DOCKER compose up -d" >&2
}

echo "=== 1/6 backup ==="
mkdir -p "$BACKUP_DIR"
chmod 700 "$STACK_DIR/release-backups" "$BACKUP_DIR"
cp "$ENV_FILE" "$BACKUP_DIR/.env.bak"; chmod 600 "$BACKUP_DIR/.env.bak"         # explicit modes: both copies hold secrets or the stack layout
cp compose.yaml "$BACKUP_DIR/compose.yaml.bak"; chmod 600 "$BACKUP_DIR/compose.yaml.bak"
# keep the newest RELEASE_BACKUPS_KEEP release folders (timestamp names only; this release's folder is never removed), log each removal
n=0
for d in $(ls -1 "$STACK_DIR/release-backups" | grep -E '^[0-9]{8}T[0-9]{6}Z$' | sort -r); do
  n=$((n + 1))
  if [ "$n" -gt "$KEEP_BACKUPS" ] && [ "$d" != "$STAMP" ]; then
    rm -rf "$STACK_DIR/release-backups/$d" && echo "pruned release-backup $d (keeping the newest $KEEP_BACKUPS)"
  fi
done
if ! "$DOCKER" compose exec -T pg-backup env RUN_ONCE=1 sh /scripts/backup.sh; then
  echo "ERROR: the database backup failed - release stopped, nothing was changed." >&2
  exit 3
fi

echo "=== 2/6 Flyway image ==="
PREFIX="${IPASS_IMAGE_PREFIX:-}"
if [ -z "$PREFIX" ]; then PREFIX="$(grep -E '^[[:space:]]*(export[[:space:]]+)?IPASS_IMAGE_PREFIX=' "$ENV_FILE" | tail -n 1 | tr -d '\r' | cut -d= -f2- | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\\(.*\\)'\$/\\1/")" || true; fi
FLYWAY_IMAGE="${FLYWAY_IMAGE:-${PREFIX:-acsnewacr.azurecr.io/ipass}/flyway-migrate:$NEW_TAG}"
export FLYWAY_IMAGE
export FW_EXPECT_REVISION="$NEW_TAG"      # migrate.sh refuses an image whose revision label is not this commit (build with flyway/build-image.sh)
if ! "$DOCKER" image inspect "$FLYWAY_IMAGE" >/dev/null 2>&1; then
  echo "Flyway image $FLYWAY_IMAGE is not on this host; trying the registry..."
  if ! "$DOCKER" pull "$FLYWAY_IMAGE"; then
    echo "ERROR: $FLYWAY_IMAGE not found. Build it on the build PC and push it, or: docker save $FLYWAY_IMAGE | ssh <vps> docker load" >&2
    exit 4
  fi
fi

MIGRATE="$STACK_DIR/flyway/migrate.sh"
echo "=== 3/6 Flyway info (what is pending) ==="
sh "$MIGRATE" info || { echo "ERROR: Flyway info failed - release stopped, nothing was changed." >&2; exit 5; }

echo "=== 4/6 Flyway validate + migrate ==="
# migrate.sh runs validate first and refuses to migrate when it fails
if ! sh "$MIGRATE" migrate; then
  echo "" >&2
  echo "!!! MIGRATION FAILED - do not deploy the new app version. The app, the tag and the containers were not touched. !!!" >&2
  echo "Postgres DDL is transactional: the failed migration rolled back on its own; migrations that finished before it stay applied." >&2
  echo "Look at the error above and ./flyway/migrate.sh info. Fix the SQL, rebuild the image (new tag) and run again; restore the dump only if data is damaged." >&2
  rollback_note
  exit 5
fi

echo "=== 5/6 switch to $NEW_TAG and start ==="
tmp="$(mktemp "$STACK_DIR/.env.new.XXXXXX")"      # same directory, so the final mv is an atomic rename; the EXIT trap removes it on any failure
if grep -qE "$TAG_LINE" "$ENV_FILE"; then
  sed -E "s/^([[:space:]]*(export[[:space:]]+)?)IMAGE_TAG=.*/\1IMAGE_TAG=$NEW_TAG/" "$ENV_FILE" > "$tmp"
else
  { cat "$ENV_FILE"; echo "IMAGE_TAG=$NEW_TAG"; } > "$tmp"
fi
chmod "$(stat -c %a "$ENV_FILE" 2>/dev/null || echo 600)" "$tmp"
mv "$tmp" "$ENV_FILE"; tmp=""       # atomic: .env is never half-written
if ! "$DOCKER" compose up -d; then
  cat "$BACKUP_DIR/.env.bak" > "$ENV_FILE"       # the old containers still run on the old tag: keep the env file consistent with them
  echo "ERROR: 'docker compose up -d' failed. The database IS migrated (migrations are expand-only, the old app still works); the env file was restored to $OLD_TAG." >&2
  echo "WARNING: 'up -d' recreates services one by one, so SOME services may already run $NEW_TAG while .env says $OLD_TAG. Current state:" >&2
  "$DOCKER" compose ps >&2 || true
  echo "Do NOT run another plain 'compose up -d' / rotate-secret.sh before deciding: it would recreate those services on $OLD_TAG against the migrated database. Either fix the cause and re-run IMAGE_TAG=$NEW_TAG ./deploy.sh, or finish the roll-back below." >&2
  rollback_note
  exit 6
fi

echo "=== 6/6 health ==="
HEALTH_SERVICES="${HEALTH_SERVICES:-postgres keycloak platform-app edge}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-300}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-5}"
waited=0
while :; do
  bad=""
  for svc in $HEALTH_SERVICES; do
    cid="$("$DOCKER" compose ps -q "$svc" 2>/dev/null || true)"
    st="none"
    [ -n "$cid" ] && st="$("$DOCKER" inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo unknown)"
    case "$st" in healthy|running) ;; *) bad="$bad $svc=$st" ;; esac
  done
  [ -z "$bad" ] && break
  if [ "$waited" -ge "$HEALTH_TIMEOUT" ]; then
    echo "ERROR: not healthy after ${HEALTH_TIMEOUT}s:$bad" >&2
    echo "Logs: $DOCKER compose logs --since 10m <service>" >&2
    rollback_note
    exit 6
  fi
  sleep "$HEALTH_INTERVAL"; waited=$((waited + HEALTH_INTERVAL))
done
echo "All of [$HEALTH_SERVICES] healthy."

echo "=== Flyway info after the release ==="
sh "$MIGRATE" info || echo "WARNING: post-release Flyway info failed (the release itself is done)" >&2
echo "=== Release $NEW_TAG complete (previous tag $OLD_TAG; backups in $BACKUP_DIR) ==="
echo "Next: README 'Update and roll back' step 3 and Runbook step 12 (outside checks)."
