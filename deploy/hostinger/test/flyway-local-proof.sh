#!/bin/bash
# Local container proof of the Flyway CLI pipeline (build PC; starts containers, so it takes the PC-wide Docker lock).
# Builds the Flyway image, starts a throwaway Postgres (the compose.yaml pin) on a throwaway network with the REAL role
# script (postgres-init/20-platform-roles.sh runs at first start, exactly as on the server), then drives migrate.sh through
# info -> validate -> migrate -> info -> migrate, a checksum-tamper refusal, and a backup/restore drill with the commands the
# README documents. Deletes everything it created. Exit 0 = every step behaved; the log shows real Flyway output.
#   bash deploy/hostinger/test/flyway-local-proof.sh [expected-migration-count]       (default: counted from the folders)
# Parity (the app's own Flyway sees nothing pending on the CLI-migrated database) runs inside (PARITY_JAVA / PARITY_JARS required).
# Step 11 proves the migrator password is absent from `docker inspect` and the host process list while Flyway runs and that the
# container is hardened; step 12 the image revision-label check. Every exit path (also failure, Ctrl-C, kill) removes what the run created.
# KEEP=1 leaves the throwaway database up for inspection: the env copy it writes is mode 600 at a printed path (a warning says so);
# the containers then stay OUTSIDE the Docker lock until you remove them with the command printed at the end.
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@"
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
LOCKROOT="${DOCKER_SUITE_LOCKROOT:-/d/Projects/ipass/.docker-suite.lock}"      # the PC-wide lock, one level above the code root (AGENTS.md)
PGIMG='postgres:17.11-alpine@sha256:eee6c6e1b0894fa4fe0075eb054af9fd1cf39a35321c2d4110626cfe506411fb'
N="fwproof-$$"; IMG="ipass/flyway-migrate:proof-$$"
LOCK=""
. "$HERE/flyway/flyway-run.sh"
export IPASS_FLYWAY_TEST=1      # the probes and the step-11 container use FW_DOCKER_ARGS, which production refuses
# the main lock is usually held by a long suite; a light run takes a numbered slot like test/edge-ops.sh does
for l in "$LOCKROOT" "$LOCKROOT-3" "$LOCKROOT-4" "$LOCKROOT-5" "$LOCKROOT-6"; do
  if mkdir "$l" 2>/dev/null; then LOCK="$l"; break; fi
done
[ -n "$LOCK" ] || { echo "no free docker lock - not started (a live holder is never taken over)"; exit 2; }
echo "arjun flyway-local-proof pid=$$ $(date -u +%FT%TZ) refreshed=$(date -u +%FT%TZ)" > "$LOCK/owner"
TMP="$(mktemp -d)"; fail=0
cleanup() {
  if [ "${KEEP:-0}" != 1 ]; then docker rm -f "$N-pg" >/dev/null 2>&1; docker network rm "$N" >/dev/null 2>&1; docker volume rm "$N-data" >/dev/null 2>&1; fi
  docker rm -f "$N-fwx" "$N-ctl" >/dev/null 2>&1; docker rmi "$IMG" >/dev/null 2>&1; [ -z "${BGPID:-}" ] || kill "$BGPID" 2>/dev/null; rm -rf "$LOCK" "$TMP"
}
trap cleanup EXIT
trap 'exit 130' INT TERM        # EXIT then runs cleanup: a failure, Ctrl-C or kill never leaves containers, volume, image, lock or temp files behind
step() { echo; echo "##### $*"; }
expect() { # description, command...
  d="$1"; shift
  if "$@"; then echo "PASS $d"; else echo "FAIL $d"; fail=1; fi
}
rnd() { openssl rand -hex 12; }
EXPECTED="${1:-$(find "$REPO" \( -name node_modules -o -name target -o -name .git \) -prune -o -type d -path '*/src/main/resources/db/migration' -print | while read -r d; do ls "$d"; done | wc -l | tr -d ' ')}"

if [ -z "${PARITY_JAVA:-}" ] || [ -z "${PARITY_JARS:-}" ]; then
  if [ "${ALLOW_NO_PARITY:-0}" = 1 ]; then echo "WARNING: parity NOT run (PARITY_JAVA / PARITY_JARS not set, ALLOW_NO_PARITY=1): this is not a full proof"; PARITY_RAN=0
  else echo "FAIL: PARITY_JAVA and PARITY_JARS are required (the parity check is the point of this proof); ALLOW_NO_PARITY=1 runs without it and never ends in ALL PASSED"; fail=1; fi
fi

step "0. guard"
expect "check-migration-dirs.sh passes on the real tree" sh "$HERE/flyway/check-migration-dirs.sh" "$REPO"

# the guard must fail when a folder is left out of the Dockerfile
grep -v "COPY platform-routing/" "$HERE/flyway/Dockerfile" > "$TMP/Dockerfile.omit"
sh "$HERE/flyway/check-migration-dirs.sh" "$REPO" "$TMP/Dockerfile.omit" > "$TMP/omit.out" 2>&1; rc=$?; cat "$TMP/omit.out"
expect "guard FAILS (exit 1) when platform-routing is omitted from the Dockerfile" bash -c "[ $rc -eq 1 ] && grep -q 'platform-routing/src/main/resources/db/migration exists' '$TMP/omit.out'"

step "1. build the Flyway image ($EXPECTED migrations expected)"
expect "docker build" docker build -q -f "$HERE/flyway/Dockerfile" --label "org.opencontainers.image.revision=proofrev$$" -t "$IMG" "$REPO"
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

# parity: the app's Flyway (FlywayParityCheck.java) on this database. Needs PARITY_JAVA (java binary) and PARITY_JARS (flyway-core,
# flyway-database-postgresql, the Postgres driver, jackson-databind...) - skipped, loudly, when they are not given.
PARITY_RAN=0
parity() { # expected-exit
  [ -n "${PARITY_JAVA:-}" ] && [ -n "${PARITY_JARS:-}" ] || return 99
  PARITY_RAN=1
  sep=:; [ "$(uname -o 2>/dev/null)" = Msys ] && sep=";"
  dirs=""; for d in $(find "$REPO" \( -name node_modules -o -name target -o -name .git \) -prune -o -type d -path '*/src/main/resources' -print); do
    [ -d "$d/db/migration" ] && dirs="$dirs${dirs:+$sep}$(cygpath -w "$d" 2>/dev/null || echo "$d")"; done
  PARITY_DB_PASSWORD="$PW_MIG" "$PARITY_JAVA" -cp "$dirs$sep$PARITY_JARS" "$(cygpath -w "$HERE/test/FlywayParityCheck.java" 2>/dev/null || echo "$HERE/test/FlywayParityCheck.java")" "jdbc:postgresql://127.0.0.1:$HOSTPORT/ipass" ipass_migrator 2>&1 | tail -3
  return "${PIPESTATUS[0]}"
}

step "3. info on the empty database (all pending)"
mig info > "$TMP/i1.out" 2>&1; rc=$?; cat "$TMP/i1.out" | tail -12
expect "info exit 0" test $rc -eq 0
expect "$EXPECTED migrations listed Pending" test "$(grep -c '| Pending' "$TMP/i1.out")" -eq "$EXPECTED"

parity > "$TMP/p0.out" 2>&1; rc=$?; cat "$TMP/p0.out"
expect "control: the app's Flyway sees $EXPECTED pending before the CLI ran (check is not vacuous)" bash -c "[ $rc -eq 1 ] && grep -q 'pending=$EXPECTED ' '$TMP/p0.out'"

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

parity > "$TMP/p1.out" 2>&1; rc=$?; cat "$TMP/p1.out"
expect "PARITY: the app's own Flyway finds 0 pending and validate OK on the CLI-migrated database" bash -c "[ $rc -eq 0 ] && grep -q 'pending=0 validate=OK' '$TMP/p1.out'"

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
PGPASSWORD="$PW_BAK" docker exec -e PGPASSWORD "$N-pg" sh -c "pg_dump -Fc -h 127.0.0.1 -U ipass_backup -f /tmp/ipass-drill.dump ipass && pg_restore -l /tmp/ipass-drill.dump >/dev/null && ls -l /tmp/ipass-drill.dump"
docker exec "$N-pg" psql -U ipass_admin -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE ipass WITH (FORCE)" -c "CREATE DATABASE ipass OWNER ipass_migrator"
docker exec "$N-pg" sh -c "cat /tmp/ipass-drill.dump | pg_restore -U ipass_admin -d ipass --exit-on-error"
expect "restore exit 0" test $? -eq 0
mig info > "$TMP/i3.out" 2>&1
expect "after the restore: $EXPECTED migrations Success, none Pending" bash -c "[ \"\$(grep -c '| Success' '$TMP/i3.out')\" -eq $EXPECTED ] && ! grep -q '| Pending' '$TMP/i3.out'"
dbo="$(docker exec "$N-pg" psql -U ipass_admin -d ipass -Atc "select pg_get_userbyid(datdba) from pg_database where datname='ipass'" | tr -d '\r')"
tbo="$(docker exec "$N-pg" psql -U ipass_admin -d ipass -Atc "select tableowner from pg_tables where tablename='flyway_schema_history'" | tr -d '\r')"
echo "database owner=$dbo, flyway_schema_history owner=$tbo"
expect "database owner and a table owner are ipass_migrator again" test "$dbo,$tbo" = ipass_migrator,ipass_migrator
mig migrate >/dev/null 2>&1; expect "migrate after the restore is a no-op (exit 0)" test $? -eq 0

step "10. the init SQL (lock_timeout) really reaches the migration session"
# a probe migration raises unless current_setting('lock_timeout') is the expected value; the default of a Postgres session is 0,
# so a pass proves the setting was applied. Each probe has its own schema and history table; the wrong-expectation probes must FAIL.
probe() { # name expected-timeout extra-flyway-args...
  pn="$1"; want="$2"; shift 2
  mkdir -p "$TMP/probe-$pn"
  printf "DO \$\$ BEGIN IF current_setting('lock_timeout') <> '%s' THEN RAISE EXCEPTION 'lock_timeout=%%', current_setting('lock_timeout'); END IF; END \$\$;\n" "$want" > "$TMP/probe-$pn/V1__probe.sql"
  FW_IMAGE="$IMG"; FW_NETWORK="$N"; FW_URL=jdbc:postgresql://postgres:5432/ipass; FW_DB_USER=ipass_migrator; FW_DB_PASSWORD="$PW_MIG"
  FW_DOCKER_ARGS="-v $(cygpath -w "$TMP/probe-$pn" 2>/dev/null || echo "$TMP/probe-$pn"):/probe:ro"     flyway_run -locations=filesystem:/probe "-schemas=probe_$pn" "-table=h_$pn" "$@" migrate 2>&1 | grep -E "Successfully applied|lock_timeout=|ERROR" | head -6
  return "${PIPESTATUS[0]}"
}
probe a 5s > "$TMP/pa.out"; rc=$?; cat "$TMP/pa.out"; expect "bare image run (flyway.toml initSql): SHOW lock_timeout = 5s inside the migration" test $rc -eq 0
probe b 9s "-initSql=SET lock_timeout = '9s'" > "$TMP/pb.out"; rc=$?; cat "$TMP/pb.out"; expect "-initSql override (what migrate.sh passes for IPAAS_FLYWAY_LOCK_TIMEOUT=9s): lock_timeout = 9s" test $rc -eq 0
probe c 9s > "$TMP/pc.out"; rc=$?; cat "$TMP/pc.out"; expect "control: a bare run does NOT see 9s (probe fails with lock_timeout=5s)" bash -c "[ $rc -ne 0 ] && grep -q 'lock_timeout=5s' '$TMP/pc.out'"
probe d 5s "-initSql=SET lock_timeout = '9s'" > "$TMP/pd.out"; rc=$?; cat "$TMP/pd.out"; expect "control: the override replaces the toml value (probe fails with lock_timeout=9s)" bash -c "[ $rc -ne 0 ] && grep -q 'lock_timeout=9s' '$TMP/pd.out'"
IPAAS_FLYWAY_LOCK_TIMEOUT=9s mig info > "$TMP/pe.out" 2>&1; expect "migrate.sh with IPAAS_FLYWAY_LOCK_TIMEOUT=9s still runs (arg accepted by the 12.4.0 CLI)" test $? -eq 0

step "11. the migrator password is not in docker inspect or the host process list while Flyway runs; the container is hardened"
mkdir -p "$TMP/fwtmp"
FW_IMAGE="$IMG"; FW_NETWORK="$N"; FW_URL=jdbc:postgresql://postgres:5432/ipass; FW_DB_USER=ipass_migrator; FW_DB_PASSWORD="$PW_MIG"
hostps() { if command -v powershell.exe >/dev/null 2>&1; then powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process | ForEach-Object { \$_.CommandLine }" 2>/dev/null; else ps -eo args; fi; }
( TMPDIR="$TMP/fwtmp" FW_DOCKER_ARGS="--name $N-fwx" flyway_run "-initSql=SELECT pg_sleep(25)" info > "$TMP/x.out" 2>&1; echo $? > "$TMP/x.rc" ) &
BGPID=$!
for i in $(seq 1 40); do [ -n "$(docker ps -q -f "name=$N-fwx")" ] && break; sleep 1; done
sleep 4
docker inspect "$N-fwx" > "$TMP/x.inspect" 2>&1; hostps > "$TMP/x.ps" 2>&1
expect "the Flyway container is running (inspect has content)" grep -q '"Config"' "$TMP/x.inspect"
expect "password ABSENT from docker inspect (whole JSON) of the running Flyway container" bash -c "! grep -qF '$PW_MIG' '$TMP/x.inspect'"
expect "no FLYWAY_PASSWORD variable in Config.Env" bash -c "! grep -q 'FLYWAY_PASSWORD' '$TMP/x.inspect'"
expect "password ABSENT from the host process list while it runs" bash -c "! grep -qF '$PW_MIG' '$TMP/x.ps'"
expect "while it runs: secret dir mode 700, secret file mode 600" bash -c "[ \"\$(stat -c %a \"$TMP\"/fwtmp/flyway-secret.*)\" = 700 ] && [ \"\$(stat -c %a \"$TMP\"/fwtmp/flyway-secret.*/secret.toml)\" = 600 ]"
docker inspect -f 'readonly={{.HostConfig.ReadonlyRootfs}} capdrop={{.HostConfig.CapDrop}} secopt={{.HostConfig.SecurityOpt}} user={{.Config.User}} pids={{.HostConfig.PidsLimit}} mem={{.HostConfig.Memory}}' "$N-fwx"
expect "hardened: read-only root, cap-drop ALL, no-new-privileges, non-root user" bash -c "docker inspect -f 'readonly={{.HostConfig.ReadonlyRootfs}} capdrop={{.HostConfig.CapDrop}} secopt={{.HostConfig.SecurityOpt}} user={{.Config.User}}' '$N-fwx' | grep -E 'readonly=true capdrop=\[ALL\] secopt=\[no-new-privileges:true\] user=[1-9][0-9]*:'"
# positive controls: the same checks DO find the password when it is passed the old way
docker run -d --name "$N-ctl" --network "$N" -e FLYWAY_PASSWORD="$PW_MIG" --entrypoint sleep "$IMG" 30 >/dev/null
docker inspect "$N-ctl" > "$TMP/c.inspect" 2>&1
expect "control: the inspect check WOULD find the password if it were passed with -e" grep -qF "$PW_MIG" "$TMP/c.inspect"
docker run --rm --name "$N-ctl2" --entrypoint sh "$IMG" -c 'sleep 20' x "$PW_MIG" >/dev/null 2>&1 & CPID=$!
sleep 3; hostps > "$TMP/c.ps" 2>&1; kill $CPID 2>/dev/null; docker rm -f "$N-ctl2" >/dev/null 2>&1
expect "control: the process-list check WOULD find a password given as a command-line argument" grep -qF "$PW_MIG" "$TMP/c.ps"
docker rm -f "$N-ctl" >/dev/null 2>&1
wait "$BGPID"; BGPID=""
expect "info under the hardened flags: exit 0 and the 59 migrations listed" bash -c "[ \"\$(cat '$TMP/x.rc')\" = 0 ] && [ \"\$(grep -c '| Success' '$TMP/x.out')\" -eq $EXPECTED ]"
expect "the secret directory is gone once the container exited" test -z "$(ls -A "$TMP/fwtmp")"

step "12. the image must carry the expected commit (revision label) before the password is used"
FW_EXPECT_REVISION="proofrev$$" mig info >/dev/null 2>&1; expect "matching revision label: info runs (exit 0)" test $? -eq 0
FW_EXPECT_REVISION="otherrev" mig info > "$TMP/l.out" 2>&1; rc=$?; head -2 "$TMP/l.out"
expect "wrong revision label: refused with exit 4, no container started" test $rc -eq 4
FLYWAY_IMAGE_ID="sha256:wrong" mig info >/dev/null 2>&1; expect "wrong FLYWAY_IMAGE_ID: refused with exit 4" test $? -eq 4

if [ "${KEEP:-0}" = 1 ]; then
  KEPT="$(umask 077; mktemp "${TMPDIR:-/tmp}/flyway-proof-env.XXXXXX")"; cp "$TMP/.env" "$KEPT"; chmod 600 "$KEPT"
  echo; echo "WARNING: KEEP=1 - the throwaway database stays up: jdbc:postgresql://127.0.0.1:$HOSTPORT/ipass, user ipass_migrator, password in $KEPT (mode 600). It is a throwaway value, but delete the file when you are done."
  echo "remove with: docker rm -f $N-pg; docker network rm $N; docker volume rm $N-data; rm -f $KEPT"
  expect "KEEP: the kept env copy is mode 600" test "$(stat -c %a "$KEPT")" = 600
fi
echo
if [ $fail -ne 0 ]; then echo "FAILURES"
elif [ "$PARITY_RAN" != 1 ]; then echo "PASSED WITHOUT PARITY - not a full proof (parity check did not run)"; fail=1
else echo "ALL PASSED (parity ran)"; fi
exit $fail
