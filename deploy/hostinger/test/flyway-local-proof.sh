#!/bin/bash
# Local container proof of the Flyway CLI pipeline (build PC; starts containers, so it takes the PC-wide Docker lock).
# Builds the Flyway image, starts a throwaway Postgres (the compose.yaml pin) on a throwaway network with the REAL role
# script (postgres-init/20-platform-roles.sh runs at first start, exactly as on the server), then drives migrate.sh through
# info -> validate -> migrate -> info -> migrate, a checksum-tamper refusal, and a backup/restore drill with the commands the
# README documents. Deletes everything it created. Exit 0 = every step behaved; the log shows real Flyway output.
#   bash deploy/hostinger/test/flyway-local-proof.sh [expected-migration-count]       (default: counted from the folders)
# The parity step (the app's own Flyway sees nothing pending on the CLI-migrated database) is separate: README "Migration
# pipeline" > Parity check; set KEEP=1 to leave the database up for it (you then remove the three objects named at the end).
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@"
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
LOCKROOT="${DOCKER_SUITE_LOCKROOT:-$(cd "$REPO/.." && pwd)/.docker-suite.lock}"
PGIMG='postgres:17.11-alpine@sha256:eee6c6e1b0894fa4fe0075eb054af9fd1cf39a35321c2d4110626cfe506411fb'
N="fwproof-$$"; IMG="ipass/flyway-migrate:proof-$$"
LOCK=""
# the main lock is usually held by a long suite; a light run takes a numbered slot like test/edge-ops.sh does
for l in "$LOCKROOT" "$LOCKROOT-3" "$LOCKROOT-4" "$LOCKROOT-5" "$LOCKROOT-6"; do
  if mkdir "$l" 2>/dev/null; then LOCK="$l"; break; fi
done
[ -n "$LOCK" ] || { echo "no free docker lock - not started (a live holder is never taken over)"; exit 2; }
echo "arjun flyway-local-proof pid=$$ $(date -u +%FT%TZ) refreshed=$(date -u +%FT%TZ)" > "$LOCK/owner"
TMP="$(mktemp -d)"; fail=0
cleanup() {
  if [ "${KEEP:-0}" != 1 ]; then docker rm -f "$N-pg" >/dev/null 2>&1; docker network rm "$N" >/dev/null 2>&1; docker volume rm "$N-data" >/dev/null 2>&1; fi
  docker rmi "$IMG" >/dev/null 2>&1; rm -rf "$LOCK" "$TMP"
}
trap cleanup EXIT
step() { echo; echo "##### $*"; }
expect() { # description, command...
  d="$1"; shift
  if "$@"; then echo "PASS $d"; else echo "FAIL $d"; fail=1; fi
}
rnd() { openssl rand -hex 12; }
EXPECTED="${1:-$(find "$REPO" \( -name node_modules -o -name target -o -name .git \) -prune -o -type d -path '*/src/main/resources/db/migration' -print | while read -r d; do ls "$d"; done | wc -l | tr -d ' ')}"

step "0. guard"
expect "check-migration-dirs.sh passes on the real tree" sh "$HERE/flyway/check-migration-dirs.sh" "$REPO"

# the guard must fail when a folder is left out of the Dockerfile
grep -v "COPY platform-routing/" "$HERE/flyway/Dockerfile" > "$TMP/Dockerfile.omit"
sh "$HERE/flyway/check-migration-dirs.sh" "$REPO" "$TMP/Dockerfile.omit" > "$TMP/omit.out" 2>&1; rc=$?; cat "$TMP/omit.out"
expect "guard FAILS (exit 1) when platform-routing is omitted from the Dockerfile" bash -c "[ $rc -eq 1 ] && grep -q 'platform-routing/src/main/resources/db/migration exists' '$TMP/omit.out'"

step "1. build the Flyway image ($EXPECTED migrations expected)"
expect "docker build" docker build -q -f "$HERE/flyway/Dockerfile" -t "$IMG" "$REPO"
docker run --rm --entrypoint sh "$IMG" -c 'ls /flyway/sql | wc -l; ls /flyway/conf; id -un' | tr '\n' ' '; echo
expect "image holds $EXPECTED SQL files" test "$(docker run --rm --entrypoint sh "$IMG" -c 'ls /flyway/sql | wc -l' | tr -d ' \r')" = "$EXPECTED"
expect "image holds no password or connection value" bash -c "! docker run --rm --entrypoint sh '$IMG' -c 'cat /flyway/conf/flyway.toml; env' | grep -qE '^(password|user|url) *=|^FLYWAY_(PASSWORD|URL|USER)='"

step "2. throwaway Postgres on its own network (the real role script runs at first start)"
PW_ADMIN="$(rnd)"; PW_MIG="$(rnd)"; PW_APP="$(rnd)"; PW_BAK="$(rnd)"; PW_RST="$(rnd)"; PW_KC="$(rnd)"
docker network create "$N" >/dev/null
docker volume create "$N-data" >/dev/null
docker run -d --name "$N-pg" --network "$N" --network-alias postgres -p 127.0.0.1::5432 \
  -e POSTGRES_USER=ipass_admin -e POSTGRES_DB=ipass -e POSTGRES_PASSWORD="$PW_ADMIN" -e TZ=Etc/UTC \
  -e DB_MIGRATION_PASSWORD="$PW_MIG" -e DB_PASSWORD="$PW_APP" -e BACKUP_DB_PASSWORD="$PW_BAK" -e RESTORE_DB_PASSWORD="$PW_RST" \
  -e KEYCLOAK_DB_NAME=keycloak -e KEYCLOAK_DB_USERNAME=keycloak -e KEYCLOAK_DB_PASSWORD="$PW_KC" \
  -v "$N-data:/var/lib/postgresql/data" -v "$(cd "$HERE/postgres-init" && (pwd -W 2>/dev/null || pwd)):/docker-entrypoint-initdb.d:ro" \
  "$PGIMG" postgres -c shared_buffers=128MB -c max_connections=50 >/dev/null
for i in $(seq 1 60); do
  # the init scripts restart the server once; wait for the second, real start (the roles exist)
  if docker exec "$N-pg" psql -U ipass_admin -d ipass -Atc "select 1 from pg_roles where rolname='ipass_restore'" 2>/dev/null | grep -q 1; then break; fi
  sleep 2
done
docker exec "$N-pg" psql -U ipass_admin -d ipass -Atc "select rolname, rolcanlogin, rolcreaterole, rolbypassrls from pg_roles where rolname like 'ip%' order by 1"
expect "ipass_migrator owns the database" test "$(docker exec "$N-pg" psql -U ipass_admin -d ipass -Atc "select pg_get_userbyid(datdba) from pg_database where datname='ipass'" | tr -d '\r')" = ipass_migrator
HOSTPORT="$(docker port "$N-pg" 5432/tcp | head -1 | sed 's/.*://')"

# env file in the shape of the server's .env (values are throwaway)
umask 077
printf 'POSTGRES_DB=ipass\nIMAGE_TAG=proof-%s\nIPASS_IMAGE_PREFIX=ipass\nDB_MIGRATION_PASSWORD=%s\n' "$$" "$PW_MIG" > "$TMP/.env"
mig() { ENV_FILE="$TMP/.env" FLYWAY_NETWORK="$N" FLYWAY_IMAGE="$IMG" sh "$HERE/flyway/migrate.sh" "$@"; }

step "3. info on the empty database (all pending)"
mig info > "$TMP/i1.out" 2>&1; rc=$?; cat "$TMP/i1.out" | tail -12
expect "info exit 0" test $rc -eq 0
expect "$EXPECTED migrations listed Pending" test "$(grep -c '| Pending' "$TMP/i1.out")" -eq "$EXPECTED"

step "4. validate on the empty database (pending is not an error)"
mig validate > "$TMP/v1.out" 2>&1; rc=$?; tail -5 "$TMP/v1.out"
expect "validate exit 0" test $rc -eq 0

step "5. migrate"
mig migrate > "$TMP/m1.out" 2>&1; rc=$?; tail -8 "$TMP/m1.out"
expect "migrate exit 0" test $rc -eq 0
expect "Successfully applied $EXPECTED migrations" grep -q "Successfully applied $EXPECTED migration" "$TMP/m1.out"

step "6. info again"
mig info > "$TMP/i2.out" 2>&1; rc=$?
expect "info exit 0" test $rc -eq 0
expect "$EXPECTED migrations Success, none Pending" bash -c "[ \"\$(grep -c '| Success' '$TMP/i2.out')\" -eq $EXPECTED ] && ! grep -q '| Pending' '$TMP/i2.out'"
grep -E "Success|Pending" "$TMP/i2.out" | sed -n '1,3p;$p'
docker exec "$N-pg" psql -U ipass_admin -d ipass -Atc "select count(*), count(*) filter (where success) from flyway_schema_history"

step "7. migrate again (no-op)"
mig migrate > "$TMP/m2.out" 2>&1; rc=$?; tail -5 "$TMP/m2.out"
expect "second migrate exit 0 and nothing to apply" bash -c "[ $rc -eq 0 ] && grep -qE 'up to date|No migration necessary' '$TMP/m2.out'"

step "8. validate refuses a tampered history (checksum), and migrate then refuses to run"
V="$(docker exec "$N-pg" psql -U ipass_admin -d ipass -Atc "select checksum from flyway_schema_history where installed_rank=2" | tr -d '\r')"
docker exec "$N-pg" psql -U ipass_admin -d ipass -Atc "update flyway_schema_history set checksum=checksum+1 where installed_rank=2" >/dev/null
mig validate > "$TMP/v2.out" 2>&1; rc=$?; grep -iE "checksum|ERROR|FAILED" "$TMP/v2.out" | head -4
expect "validate exit 3 on a checksum mismatch" bash -c "[ $rc -eq 3 ] && grep -qi 'checksum' '$TMP/v2.out'"
mig migrate > "$TMP/m3.out" 2>&1; rc=$?; grep -E "VALIDATE FAILED|Successfully|MIGRATION FAILED" "$TMP/m3.out"
expect "migrate refuses (exit 3, no migrate run)" bash -c "[ $rc -eq 3 ] && ! grep -q 'Successfully applied' '$TMP/m3.out' && ! grep -q 'Migrating schema' '$TMP/m3.out'"
docker exec "$N-pg" psql -U ipass_admin -d ipass -Atc "update flyway_schema_history set checksum=$V where installed_rank=2" >/dev/null
mig validate >/dev/null 2>&1; expect "validate passes again after the checksum is put back" test $? -eq 0
mig repair > "$TMP/r0.out" 2>&1; expect "repair without --yes is refused (exit 1)" test $? -eq 1

step "9. backup and restore drill with the README commands (pg_dump -Fc as ipass_backup, restore as the admin into a recreated database)"
docker exec "$N-pg" sh -c "PGPASSWORD='$PW_BAK' pg_dump -Fc -h 127.0.0.1 -U ipass_backup -f /tmp/ipass-drill.dump ipass && pg_restore -l /tmp/ipass-drill.dump >/dev/null && ls -l /tmp/ipass-drill.dump"
docker exec "$N-pg" psql -U ipass_admin -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE ipass WITH (FORCE)" -c "CREATE DATABASE ipass OWNER ipass_migrator"
docker exec "$N-pg" sh -c "cat /tmp/ipass-drill.dump | pg_restore -U ipass_admin -d ipass --exit-on-error"
expect "restore exit 0" test $? -eq 0
mig info > "$TMP/i3.out" 2>&1
expect "after the restore: $EXPECTED migrations Success, none Pending" bash -c "[ \"\$(grep -c '| Success' '$TMP/i3.out')\" -eq $EXPECTED ] && ! grep -q '| Pending' '$TMP/i3.out'"
expect "database owner and a table owner are ipass_migrator again" bash -c "[ \"\$(docker exec '$N-pg' psql -U ipass_admin -d ipass -Atc \"select pg_get_userbyid(datdba) || ',' || (select tableowner from pg_tables where tablename='flyway_schema_history')\" | tr -d '\r')\" = ipass_migrator,ipass_migrator ]"
mig migrate >/dev/null 2>&1; expect "migrate after the restore is a no-op (exit 0)" test $? -eq 0

if [ "${KEEP:-0}" = 1 ]; then
  echo; echo "KEEP=1: database left up for the parity check: jdbc:postgresql://127.0.0.1:$HOSTPORT/ipass user ipass_migrator password in $TMP/.env (deleted on exit of THIS script: copy it now)"
  echo "remove with: docker rm -f $N-pg; docker network rm $N; docker volume rm $N-data"
  cp "$TMP/.env" "$REPO/../.flyway-proof.env" 2>/dev/null || true
fi
echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"
exit $fail
