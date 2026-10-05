#!/usr/bin/env bash
# Lane B proof harness (T-014, T-018, T-020) - the SQL-layer scenario runner for the lane B
# configuration schema. Applies the whole deployment's migration sequence through psql the way a
# deployment is (roles per V1_3_1, owner-applied migrations, restricted app login) and runs the
# scenario files, whose probes record the SQLSTATE that fired for each step.
#
# Usage: run-proof.sh red|green
#   red   - applies only the OTHER lanes' migrations; the probes must run RED (schema absent).
#   green - applies the other lanes' migrations AND the lane B V2_2_* files; probes run GREEN.
#
# Machine condition honored (plan:98): ONE docker suite at a time - the whole run sits inside the
# /d/Projects/ipass/.docker-suite.lock, released on every exit path. The Flyway-driven
# Testcontainers proof (the same scenarios as xUnit @Tag("container")) follows once the pom deps
# for platform-config land (Flyway, platform-tenancy, the PostgreSQL driver) - requested, not
# assumed.

set -u

# Git Bash would rewrite the container's /tmp path in docker-exec arguments (Windows path mangling);
# exclude exactly that prefix, all other paths (docker cp sources) convert as usual.
export MSYS2_ARG_CONV_EXCL="/tmp"

PHASE="${1:-}"
case "$PHASE" in
    red|green) ;;
    *) echo "usage: run-proof.sh red|green" >&2; exit 2 ;;
esac

REPO_ROOT="$(cd "$(dirname "$0")" && git rev-parse --show-toplevel)"
PROOF_DIR="$(cd "$(dirname "$0")" && pwd)"
PG_IMAGE="postgres:17.11-alpine"
CONTAINER="laneb-proof-${PHASE}-$$"
LOCK_DIR=/d/Projects/ipass/.docker-suite.lock

cleanup() {
    docker rm -f "$CONTAINER" >/dev/null 2>&1
    rm -rf "$LOCK_DIR"
}
trap cleanup EXIT INT TERM

# --- the one-suite-at-a-time lock; a stale lock (older than 60 minutes) is taken over ---------
if [ -f "$LOCK_DIR/owner" ]; then
    if [ -n "$(find "$LOCK_DIR/owner" -mmin +60 2>/dev/null | head -1)" ]; then
        echo "docker-suite lock stale (older than 60 minutes), owned by:" >&2
        cat "$LOCK_DIR/owner" >&2
        echo "removing it and taking the lock" >&2
        rm -rf "$LOCK_DIR"
    fi
fi
until mkdir "$LOCK_DIR" 2>/dev/null; do sleep 20; done
echo "laneB-t35 proof $PHASE $(date -u)" > "$LOCK_DIR/owner"

# --- the proof database ----------------------------------------------------------------------
# The image requires a superuser password; every psql access happens over the container's own
# socket via docker exec, and the role passwords below are random per run (R07).
docker run --rm -d --name "$CONTAINER" -e POSTGRES_PASSWORD="root-$RANDOM-laneb-proof" "$PG_IMAGE" >/dev/null || {
    echo "FAIL: cannot start $PG_IMAGE" >&2; exit 4; }
for _ in $(seq 1 90); do
    docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1 && break
    sleep 1
done
docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1 || {
    echo "FAIL: postgres never became ready" >&2; exit 4; }

OWNER_PASSWORD="lb$(tr -dc a-zA-Z0-9 </dev/urandom | head -c 24)"
APP_PASSWORD="lb$(tr -dc a-zA-Z0-9 </dev/urandom | head -c 24)"

# --- foundation (superuser): the migration owner only; ipaas_app comes from V1_3_1 ------------
docker cp "$PROOF_DIR/00_foundation_superuser.sql" "$CONTAINER:/tmp/000.sql" >/dev/null
echo "== foundation (superuser)"
docker exec -w /tmp -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -v owner_password="$OWNER_PASSWORD" \
    -U postgres -q -f 000.sql || exit 4

# --- migrations (owner), Flyway version order: lexicographic over Vx_y_z names ---------------
MIGRATION_LIST=""
for mod in platform-tenancy platform-ingest platform-config platform-delivery; do
    if [ "$mod" = platform-config ] && [ "$PHASE" = red ]; then continue; fi
    for f in "$REPO_ROOT/$mod/src/main/resources/db/migration"/V*.sql; do
        MIGRATION_LIST="$MIGRATION_LIST$(basename "$f")|$f"$'
'
    done
done
echo "== migrations (owner), version order; phase=$PHASE"
echo "$MIGRATION_LIST" | sort -t'|' -k1,1 | while IFS= read -r entry; do
    [ -z "$entry" ] && continue
    f="${entry#*|}"
    base="$(basename "$f")"
    docker cp "$f" "$CONTAINER:/tmp/m.sql" >/dev/null
    echo "   applying $base"
    docker exec -w /tmp -e PGPASSWORD="$OWNER_PASSWORD" -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -q \
        -U ipaas_migrator -d postgres -f m.sql </dev/null || exit 4
done || exit 4

# --- proof setup (owner): probe table, helper probes, tenants, the app login -----------------
echo "== proof setup (owner)"
docker cp "$PROOF_DIR/01_before_migrations_owner.sql" "$CONTAINER:/tmp/010.sql" >/dev/null
docker exec -w /tmp -e PGPASSWORD="$OWNER_PASSWORD" -i "$CONTAINER" psql -v ON_ERROR_STOP=1 \
    -v owner_password="$OWNER_PASSWORD" -v app_password="$APP_PASSWORD" \
    -U ipaas_migrator -d postgres -f 010.sql || exit 4

# --- the scenario and verification files -----------------------------------------------------
APP() { docker exec -w /tmp -e PGPASSWORD="$APP_PASSWORD" -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -qAt -U ipaas_app_login -d postgres "$@"; }
OWN() { docker exec -w /tmp -e PGPASSWORD="$OWNER_PASSWORD" -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -qAt -U ipaas_migrator -d postgres "$@"; }

for step in 20_scenario_app 21_scenario_submit_app 22_scenario_approvals \
            23_scenario_direct_owner 24_scenario_publish_app \
            30_verification_owner 31_verification_app; do
    case "$step" in
        23_scenario_direct_owner|30_verification_owner) runner=OWN ;;
        *) runner=APP ;;
    esac
    docker cp "$PROOF_DIR/$step.sql" "$CONTAINER:/tmp/$step.sql" >/dev/null
    echo "== $step"
    $runner -f "$step.sql" || exit 4
done

# --- verdict ----------------------------------------------------------------------------------
echo "== verdict"
docker cp "$PROOF_DIR/99_report.sql" "$CONTAINER:/tmp/099.sql" >/dev/null
if OWN -f 099.sql; then
    echo "PROOF RESULT: GREEN CONFIRMED (phase $PHASE)"
    [ "$PHASE" = green ]
else
    echo "PROOF RESULT: RED CONFIRMED (phase $PHASE)"
    [ "$PHASE" = red ]
fi