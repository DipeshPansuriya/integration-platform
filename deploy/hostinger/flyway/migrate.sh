#!/bin/sh
# Standalone Flyway CLI runner for the iPass stack on the Hostinger VPS (run from the stack folder, /docker/ipass).
#   ./flyway/migrate.sh info             what is applied / pending (read-only)
#   ./flyway/migrate.sh validate         applied history agrees with the files: checksums, missing files (read-only; pending is not an error)
#   ./flyway/migrate.sh migrate          validate first, then apply every pending migration; refuses to run if validate fails
#   ./flyway/migrate.sh repair --yes     rewrites flyway_schema_history (drops failed rows, re-aligns checksums); needs --yes
# The app keeps running Flyway at start-up as before (SPRING_FLYWAY_ENABLED is untouched); this runs the same migrations
# BEFORE the app is deployed. After this ran, the app's own start-up Flyway finds nothing pending.
#
# Settings, in the order compose itself uses: the process environment, then the env file (default ./.env, override ENV_FILE):
#   IMAGE_TAG (required)        the tag of the Flyway image = the commit being released (a caller-supplied value is kept)
#   IPASS_IMAGE_PREFIX          registry prefix, default acsnewacr.azurecr.io/ipass (same as compose.yaml)
#   POSTGRES_DB                 database name, default ipass
#   DB_MIGRATION_PASSWORD (required, only from the env file or the environment) - ipass_migrator's password
#   IPAAS_FLYWAY_LOCK_TIMEOUT   the app's variable for the migration lock_timeout (a Postgres duration: 5s, 500ms, 1min, 0 = off); default 5s
# Other overrides: FLYWAY_IMAGE (whole image name), FLYWAY_NETWORK (default ipass_ipass-data = compose project "ipass",
# network ipass-data), DOCKER (the docker binary, tests only).
# The env file is READ, never sourced or eval'd: only the keys above are picked out, one line each. The password goes
# into the container through the environment (docker run -e FLYWAY_PASSWORD), never on a command line, and is never printed.
# The env file is read the way this script reads it, which differs from compose for a value with `$` (compose interpolates it)
# or ` #` (compose drops the rest): such a DB_MIGRATION_PASSWORD is refused (bootstrap-env.sh generates neither character).
# Exit: 0 ok, 1 usage, 2 env file/password problem, 3 validate failed, 5 migrate/repair/info failed.
set -eu

ACTION="${1:-info}"
CONFIRM="${2:-}"
case "$ACTION" in
  info|validate|migrate) ;;
  repair)
    if [ "$CONFIRM" != "--yes" ]; then
      echo "repair rewrites flyway_schema_history. Run './flyway/migrate.sh info' first, take a backup, then re-run with: repair --yes" >&2
      exit 1
    fi ;;
  *) echo "Usage: $0 {info|validate|migrate|repair --yes}" >&2; exit 1 ;;
esac

DOCKER="${DOCKER:-docker}"
ENV_FILE="${ENV_FILE:-./.env}"
[ -r "$ENV_FILE" ] || { echo "ERROR: cannot read $ENV_FILE (run from /docker/ipass, or set ENV_FILE)" >&2; exit 2; }

# value of NAME in the env file: last `NAME=...` line; accepts `export NAME=`, leading blanks, CRLF, one pair of quotes
env_value() {
  grep -E "^[[:space:]]*(export[[:space:]]+)?$1=" "$ENV_FILE" | tail -n 1 | tr -d '\r' | cut -d= -f2- \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\\(.*\\)'\$/\\1/" || true
}

PW="$(env_value DB_MIGRATION_PASSWORD)"
[ -n "$PW" ] || { echo "ERROR: DB_MIGRATION_PASSWORD is not set in $ENV_FILE" >&2; exit 2; }
case "$PW" in
  *'$'*|*' #'*) echo "ERROR: DB_MIGRATION_PASSWORD in $ENV_FILE contains '\$' or ' #': compose and this script would read it differently, so the CLI and the app could use different passwords. Use a password without them (rotate-secret.sh)." >&2; exit 2 ;;
esac
LOCKT="${IPAAS_FLYWAY_LOCK_TIMEOUT:-$(env_value IPAAS_FLYWAY_LOCK_TIMEOUT)}"; LOCKT="${LOCKT:-5s}"
printf '%s' "$LOCKT" | grep -Eq '^[0-9]+(ms|s|min|h|d)?$' || { echo "ERROR: IPAAS_FLYWAY_LOCK_TIMEOUT must be a plain Postgres duration (5s, 500ms, 1min, 0)" >&2; exit 2; }
DB="${POSTGRES_DB:-$(env_value POSTGRES_DB)}"; DB="${DB:-ipass}"
TAG="${IMAGE_TAG:-$(env_value IMAGE_TAG)}"
PREFIX="${IPASS_IMAGE_PREFIX:-$(env_value IPASS_IMAGE_PREFIX)}"; PREFIX="${PREFIX:-acsnewacr.azurecr.io/ipass}"
if [ -z "${FLYWAY_IMAGE:-}" ]; then
  [ -n "$TAG" ] || { echo "ERROR: IMAGE_TAG is not set (environment or $ENV_FILE)" >&2; exit 2; }
  FLYWAY_IMAGE="$PREFIX/flyway-migrate:$TAG"
fi
NETWORK="${FLYWAY_NETWORK:-ipass_ipass-data}"

# one Flyway container, one action; extra Flyway arguments follow the action name
run_flyway() { # action [flyway args...]
  act="$1"; shift
  FLYWAY_PASSWORD="$PW" "$DOCKER" run --rm --network "$NETWORK" --cap-drop ALL --security-opt no-new-privileges:true \
    -e "FLYWAY_URL=jdbc:postgresql://postgres:5432/$DB" -e FLYWAY_USER=ipass_migrator -e FLYWAY_PASSWORD \
    "$FLYWAY_IMAGE" "$@" "-initSql=SET lock_timeout = '$LOCKT'" "$act"
}
# pending migrations are the normal state before a release: validate checks the HISTORY against the files, so ignore them;
# *:future (Flyway's and Spring Boot's default, which an explicit pattern replaces) keeps a database that is ahead of this image valid
validate() { run_flyway validate '-ignoreMigrationPatterns=*:pending,*:future'; }

echo "=== Flyway $ACTION | image $FLYWAY_IMAGE | database $DB | user ipass_migrator ==="
case "$ACTION" in
  info)
    run_flyway info || { echo "Flyway info FAILED" >&2; exit 5; } ;;
  validate)
    validate || { echo "VALIDATE FAILED: the database history and the migration files disagree - do not deploy" >&2; exit 3; } ;;
  migrate)
    validate || { echo "VALIDATE FAILED: the database history and the migration files disagree - migrate refused, do not deploy" >&2; exit 3; }
    run_flyway migrate || { echo "MIGRATION FAILED - do not deploy the new app version. See the rollback notes in deploy/hostinger/README.md (Migration pipeline)." >&2; exit 5; } ;;
  repair)
    run_flyway repair || { echo "Flyway repair FAILED" >&2; exit 5; } ;;
esac
echo "=== Flyway $ACTION done ==="
