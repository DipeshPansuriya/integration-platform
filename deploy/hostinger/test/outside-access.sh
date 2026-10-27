#!/bin/bash
# Behaviour proof of the outside Postgres door (deploy/hostinger/outside): REAL roles (postgres-init), REAL Flyway SQL of
# every module, the real pg_hba template, TLS, the real HAProxy config and outside-role.sql / -drop.sql. Build PC only,
# under the PC-wide Docker lock (not taken over: exit 2 if a live owner holds it). Nothing here touches any server.
#   bash deploy/hostinger/test/outside-access.sh
set -u
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")/.." && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
LOCK="${DOCKER_SUITE_LOCK:-/d/Projects/ipass/.docker-suite.lock}"
N=ipass-outside-t; SUBNET="${OUTSIDE_TEST_SUBNET:-172.29.213.0/28}"
PGIMG=postgres:17.11-alpine; HAIMG="haproxy:3.0.11-alpine"
mkdir "$LOCK" 2>/dev/null || { echo "docker lock held by: $(cat "$LOCK/owner" 2>/dev/null) - not started"; exit 2; }
echo "arjun ipass-outside-access $(date -u +%FT%TZ) refreshed=$(date -u +%FT%TZ)" > "$LOCK/owner"
TMP="$(mktemp -d)"
cleanup() { docker rm -f $N-pg $N-front >/dev/null 2>&1; docker network rm $N-data $N-front $N-pub >/dev/null 2>&1; docker volume rm $N-tls $N-hba >/dev/null 2>&1; rm -rf "$TMP" "$LOCK"; }
trap cleanup EXIT
fail=0; ok() { echo "PASS $1"; }; bad() { echo "FAIL $1  [$2]"; fail=1; }
rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-32; }
win() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

# ---- static: files and the compose override
check_static() {
  grep -q 'ports:' "$HERE/compose.yaml" && bad "base compose publishes a port" "" || ok "base compose.yaml publishes nothing"
  [ "$(awk '/^  [a-z-]+:$/{svc=$1} /ports:/{print svc}' "$HERE/outside/compose.outside.yaml")" = "pg-front:" ] && ok "only pg-front publishes a port" || bad "ports" ""
  grep -q '"0.0.0.0:5432:5432"' "$HERE/outside/compose.outside.yaml" && ok "published on IPv4 5432 only" || bad "publish line" ""
  awk '/^networks:/{n=1} n' "$HERE/outside/compose.outside.yaml" | grep -A2 'ipass-pgfront:' | grep -q 'internal: true' && ok "ipass-pgfront is internal" || bad "pgfront internal" ""
  ! grep -q 'ipass-data:' <(awk '/^networks:/{n=1} n' "$HERE/outside/compose.outside.yaml") && ok "ipass-data is not redefined" || bad "ipass-data touched" ""
  grep -q 'networks: \[ipass-pgfront, ipass-pgpublic\]' "$HERE/outside/compose.outside.yaml" && ok "pg-front is on pgfront+pgpublic only (not ipass-data, not web)" || bad "pg-front nets" ""
  grep -q 'enable_ip_masquerade: "false"' "$HERE/outside/compose.outside.yaml" && ok "the published network has no outbound NAT" || bad "masquerade" ""
  grep -q 'ssl=on' "$HERE/outside/compose.outside.yaml" && grep -q 'password_encryption=scram-sha-256' "$HERE/outside/compose.outside.yaml" && ok "ssl on, scram-sha-256" || bad "ssl flags" ""
  local o; o="$(DRY_RUN=1 sh "$HERE/outside/pg-throttle.sh" on 2>&1)"
  grep -q 'connlimit-above 4' <<<"$o" && grep -q 'hashlimit-above 12/minute' <<<"$o" && grep -q 'DOCKER-USER 1' <<<"$o" && ok "throttle on: connlimit + hashlimit in DOCKER-USER" || bad "throttle on" "$o"
  o="$(DRY_RUN=1 sh "$HERE/outside/pg-throttle.sh" off 2>&1)"; grep -q -- '-D DOCKER-USER' <<<"$o" && grep -q -- '-X IPASS-PG' <<<"$o" && ok "throttle off removes the jump and the chain" || bad "throttle off" "$o"
  ! IPASS_PGFRONT_SUBNET=10.0.0.0/8 sh "$HERE/outside/render-pg-hba.sh" "$TMP/x" 2>/dev/null && ! IPASS_PGFRONT_SUBNET='10.0.0.0/24;x' sh "$HERE/outside/render-pg-hba.sh" "$TMP/x" 2>/dev/null && ok "render refuses a wide or malformed subnet" || bad "render validation" ""
  if docker compose version >/dev/null 2>&1; then
    printf 'IMAGE_TAG=t\nIPASS_DOMAIN=t.invalid\nIPASS_PGFRONT_SUBNET=%s\n' "$SUBNET" > "$TMP/c.env"
    for v in POSTGRES_PASSWORD DB_MIGRATION_PASSWORD DB_PASSWORD BACKUP_DB_PASSWORD RESTORE_DB_PASSWORD KEYCLOAK_DB_PASSWORD CONSOLE_OIDC_CLIENT_SECRET PLATFORM_ADMIN_CLIENT_SECRET OPERATOR_AGENT_CLIENT_SECRET OPS_KAFBAT_CLIENT_SECRET PLATFORM_CREDENTIAL_KEK_K1; do echo "$v=dummy" >> "$TMP/c.env"; done
    docker compose --env-file "$(win "$TMP/c.env")" -f "$(win "$HERE/compose.yaml")" -f "$(win "$HERE/outside/compose.outside.yaml")" config -q 2>"$TMP/cfg.err" && ok "compose + outside override validates" || bad "compose config" "$(head -3 "$TMP/cfg.err")"
  fi
}
check_static

# ---- live: postgres with the real files
PW_ADMIN="$(rand)"; PW_MIG="$(rand)"; PW_APP="$(rand)"; PW_BAK="$(rand)"; PW_RST="$(rand)"; PW_KC="$(rand)"
sh "$HERE/outside/make-tls-cert.sh" "$TMP/tls" "pg-front,localhost,127.0.0.1" >/dev/null 2>&1 || { bad "make-tls-cert" ""; exit 1; }
IPASS_PGFRONT_SUBNET="$SUBNET" sh "$HERE/outside/render-pg-hba.sh" "$TMP/pg_hba.conf" >/dev/null || exit 1
CA="$(cat "$TMP/tls/server.crt")"
docker volume create $N-tls >/dev/null; docker volume create $N-hba >/dev/null
put() { docker run --rm -i -v "$1":/d alpine sh -c "cat > /d/$2 && chown 70:70 /d/$2 && chmod $3 /d/$2" < "$4"; }
put $N-tls server.crt 644 "$TMP/tls/server.crt"; put $N-tls server.key 600 "$TMP/tls/server.key"; put $N-hba pg_hba.conf 644 "$TMP/pg_hba.conf"
docker network create --internal $N-data >/dev/null; docker network create --internal --subnet "$SUBNET" $N-front >/dev/null; docker network create $N-pub >/dev/null
CMD="$(sed -n '/command: \[/,/log_disconnections=on"\]/p' "$HERE/outside/compose.outside.yaml" | tr -d '\n' | sed 's/.*command: \[//; s/\].*//; s/"//g; s/ *, */ /g; s/  */ /g')"
# shellcheck disable=SC2086
docker create --name $N-pg --network $N-data -e POSTGRES_USER=ipass_admin -e POSTGRES_DB=ipass -e POSTGRES_PASSWORD="$PW_ADMIN" \
  -e DB_MIGRATION_PASSWORD="$PW_MIG" -e DB_PASSWORD="$PW_APP" -e BACKUP_DB_PASSWORD="$PW_BAK" -e RESTORE_DB_PASSWORD="$PW_RST" \
  -e KEYCLOAK_DB_NAME=keycloak -e KEYCLOAK_DB_USERNAME=keycloak -e KEYCLOAK_DB_PASSWORD="$PW_KC" \
  -v $N-tls:/tls:ro -v $N-hba:/etc/postgresql:ro -v "$(win "$HERE/postgres-init"):/docker-entrypoint-initdb.d:ro" $PGIMG $CMD >/dev/null || exit 1
docker network connect --alias postgres $N-front $N-pg >/dev/null; docker start $N-pg >/dev/null
for i in $(seq 1 60); do [ "$(docker logs $N-pg 2>&1 | grep -c 'ready to accept connections')" -ge 2 ] && break; sleep 1; done
A() { docker exec -i $N-pg psql -q -U ipass_admin -d ipass -v ON_ERROR_STOP=1 "$@"; }
A -tAc "select 1" >/dev/null 2>&1 || { bad "postgres did not start with the outside flags" "$(docker logs $N-pg 2>&1 | tail -5)"; exit 1; }
ok "postgres starts with ssl=on, our hba_file and the real init roles"

# real Flyway SQL of every module, in version order, as the migration owner
( cd "$REPO" && git ls-files '*/src/main/resources/db/migration/*.sql' ) | while read -r f; do b="$(basename "$f")"; v="${b%%__*}"; v="${v#V}"; echo "${v//_/.} $f"; done | sort -V | cut -d' ' -f2- > "$TMP/order"
nmig=0; for f in $(cat "$TMP/order"); do
  if ! docker exec -i $N-pg psql -q -U ipass_migrator -d ipass -v ON_ERROR_STOP=1 -1 -f - < "$REPO/$f" >/dev/null 2>"$TMP/mig.err"; then bad "migration $f" "$(head -2 "$TMP/mig.err")"; exit 1; fi; nmig=$((nmig+1)); done
ok "$nmig real migrations applied"
A <<'SQL' >/dev/null || { bad "seed" ""; exit 1; }
INSERT INTO tenant(code,status) VALUES ('showcase-a','ACTIVE'),('showcase-b','ACTIVE');
INSERT INTO system(tenant_id,code,name,direction) SELECT id,'sys-'||code,'S '||code,'BOTH' FROM tenant;
INSERT INTO target_endpoint(tenant_id,system_id,code,name,base_url,http_method,auth_method,timeout_ms)
  SELECT s.tenant_id,s.id,'ep','EP','https://x.invalid/','POST','NO_AUTH',1000 FROM system s;
INSERT INTO source_credential(tenant_id,source_system_id,customer_id,issuer,client_id)
  SELECT s.tenant_id,s.id,'c1','https://i.invalid/'||s.code,'client-'||s.code FROM system s;
INSERT INTO delivery(tenant_id,state,claim_token) SELECT id,'PENDING',gen_random_uuid() FROM tenant;
SQL
ok "two tenants seeded (showcase-a, showcase-b) with a source credential and a delivery each"

# the role, through the real wrapper (password via env + .env, never printed)
printf 'POSTGRES_USER=ipass_admin\n' > "$TMP/.env"
out="$(ENV_FILE="$TMP/.env" ROTATION_LOG="$TMP/rot" PSQL_EXEC="docker exec -i -e OUT_PW $N-pg" sh "$HERE/outside/outside-role.sh" up showcase-a 2>&1)"; rc=$?
PW_OUT="$(sed -n 's/^OUTSIDE_DB_PASSWORD=//p' "$TMP/.env")"
[ $rc -eq 0 ] && [ "${#PW_OUT}" -ge 40 ] && ok "outside-role.sh up: role created, password generated into .env" || { bad "role up" "$out"; exit 1; }
grep -qF "$PW_OUT" <<<"$out" && bad "the password was printed" "" || ok "the password is not in the script output"
grep -q 'fingerprint [0-9a-f]\{8\}' <<<"$out" && ok "only an 8-character fingerprint is printed" || bad "fingerprint" "$out"
h1="$(sha256sum "$TMP/.env" | cut -c1-16)"
ENV_FILE="$TMP/.env" ROTATION_LOG="$TMP/rot" PSQL_EXEC="docker exec -i -e OUT_PW $N-pg" sh "$HERE/outside/outside-role.sh" up showcase-a >/dev/null 2>&1 && [ "$h1" = "$(sha256sum "$TMP/.env" | cut -c1-16)" ] && ok "up is idempotent (same password kept)" || bad "idempotent" ""

# proxy
docker run -d --name $N-front --network $N-front --read-only --cap-drop ALL -v "$(win "$HERE/outside/haproxy.cfg"):/usr/local/etc/haproxy/haproxy.cfg:ro" $HAIMG >/dev/null 2>&1 || { bad "haproxy start" ""; exit 1; }
docker network connect --alias pg-front $N-pub $N-front >/dev/null
sleep 2
PGIP="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$N-front\").IPAddress}}" $N-pg)"
# cl <host> <net> <password> <user> <db> <sslmode> <sql>
cl() { docker run --rm --network "$2" -e CA="$CA" -e PGPASSWORD="$3" -e PGCONNECT_TIMEOUT=6 -e SQL="$7" $PGIMG sh -c "printf '%s\n' \"\$CA\" >/tmp/ca.crt; psql \"host=$1 port=5432 user=$4 dbname=$5 sslmode=$6 sslrootcert=/tmp/ca.crt\" -v ON_ERROR_STOP=1 -tA -c \"\$SQL\" 2>&1"; }
O() { cl pg-front $N-pub "$PW_OUT" ipass_outside "${2:-ipass}" "${3:-verify-full}" "$1"; }   # outside role through the proxy, TLS verified
r="$(O "select current_user, ssl from pg_stat_ssl s join pg_stat_activity a using (pid) where pid = pg_backend_pid()" 2>&1)"
[ "$r" = "ipass_outside|t" ] && ok "TLS ok through the proxy (verify-full against the pinned cert), login as ipass_outside" || bad "TLS login" "$r"
r="$(O "select 1" ipass disable)"; grep -qiE 'no pg_hba.conf entry|rejects connection' <<<"$r" && ok "plain (non-TLS) connection is refused" || bad "plain" "$r"
r="$(cl pg-front $N-pub "wrong-$PW_OUT" ipass_outside ipass verify-full "select 1")"; grep -qi 'password authentication failed' <<<"$r" && ok "wrong password refused" || bad "wrong pw" "$r"
for pair in "ipass_admin:$PW_ADMIN" "ipass_backup:$PW_BAK" "ipass_app:$PW_APP" "ipass_migrator:$PW_MIG" "ipass_restore:$PW_RST" "keycloak:$PW_KC" "ipaas_app:x"; do
  u="${pair%%:*}"; p="${pair#*:}"; sleep 1
  r="$(cl pg-front $N-pub "$p" "$u" ipass verify-full "select 1")"; grep -qiE 'no pg_hba.conf entry|rejects connection' <<<"$r" && ok "$u refused from the outside path (right password)" || bad "$u not refused" "$r"
done
r="$(cl pg-front $N-pub "$PW_KC" keycloak keycloak verify-full "select 1")"; grep -qiE 'no pg_hba.conf entry|rejects connection' <<<"$r" && ok "keycloak role refused on the keycloak database" || bad "keycloak db" "$r"
for d in keycloak postgres template1; do r="$(O "select 1" $d)"; grep -qiE 'no pg_hba.conf entry|rejects connection' <<<"$r" && ok "ipass_outside refused on database $d" || bad "db $d" "$r"; done
# internal path still works for the app roles (docker exec uses the socket; test the network path from the data side)
r="$(docker exec -e PGPASSWORD="$PW_APP" $N-pg psql -h "$PGIP" -U ipass_app -d ipass -tAc "select 1" 2>&1)"; grep -qiE 'no pg_hba.conf entry|rejects connection' <<<"$r" && ok "ipass_app via the proxy network address is refused" || bad "app via pgfront" "$r"

# what the role sees (RLS)
r="$(O "select string_agg(code, ',' order by code) from system")"; [ "$r" = "sys-showcase-a" ] && ok "RLS: sees only tenant showcase-a rows (system)" || bad "system rows" "$r"
r="$(O "select count(*) from tenant")"; [ "$r" = "1" ] && ok "RLS: tenant directory shows its own tenant only" || bad "tenant rows" "$r"
BID="$(A -tAc "select id from tenant where code='showcase-b'")"
r="$(O "set app.tenant_id='$BID'; select count(*) from system")"; grep -q '^0$' <<<"$r" && ok "pin: SET app.tenant_id to tenant B still shows nothing" || bad "pin" "$r"
r="$(O "reset app.tenant_id; select count(*) from system")"; grep -q '^1$' <<<"$r" && ok "reset of the setting falls back to the role default (tenant A)" || bad "reset" "$r"
r="$(O "select count(*) from delivery")"; [ "$r" = "1" ] && ok "delivery readable (tenant A only)" || bad "delivery" "$r"
for t in "select * from source_credential" "select * from target_credential" "select * from app_user" "select * from role_grant" "select * from audit_event" "select * from payload" "select * from outbox" "select * from scheduler.operator_credential" "select * from console_session.spring_session" "select claim_token from delivery" "select credential_ref from target_endpoint" "select settings from system" "select * from system" "select detail from delivery_attempt"; do
  r="$(O "$t")"; grep -qi 'permission denied' <<<"$r" && ok "denied: $t" || bad "not denied: $t" "$r"
done
r="$(O "select id, code, base_url from target_endpoint")"; grep -q 'x.invalid' <<<"$r" && ok "named safe columns are readable" || bad "safe columns" "$r"
for t in "insert into system(tenant_id,code,name,direction) values (gen_random_uuid(),'x','x','BOTH')" "update system set name='x'" "delete from system" "truncate system" "create table t(i int)" "create temp table t(i int)" "create schema s" "drop table system"; do
  r="$(O "begin read write; $t")"; grep -qi 'permission denied\|must be owner' <<<"$r" && ok "refused even with read-only switched off: $t" || bad "not refused: $t" "$r"
done
r="$(O "select ipaas_login_bootstrap('a','b')")"; grep -qi 'permission denied' <<<"$r" && ok "definer functions are not callable" || bad "definer fn" "$r"
r="$(O "set role ipass_app")"; grep -qi 'permission denied\|invalid value\|not permitted' <<<"$r" && ok "cannot SET ROLE to another role" || bad "set role" "$r"

# limits: 4th connection of the role is refused (CONNECTION LIMIT 3)
docker run -d --rm --name $N-sl --network $N-pub -e CA="$CA" -e PGPASSWORD="$PW_OUT" $PGIMG sh -c "printf '%s\n' \"\$CA\" >/tmp/ca.crt; for i in 1 2 3; do psql \"host=pg-front user=ipass_outside dbname=ipass sslmode=verify-full sslrootcert=/tmp/ca.crt\" -c 'select pg_sleep(12)' >/dev/null 2>&1 & done; wait" >/dev/null
sleep 4; r="$(O "select 1")"; grep -qi 'too many connections\|server closed\|connection' <<<"$r" && ok "4th simultaneous connection is refused (role limit 3 / proxy 3 per address)" || bad "conn limit" "$r"
docker rm -f $N-sl >/dev/null 2>&1; sleep 13

# proxy rate limit: >10 new connections in 10 s from one address are cut at the proxy
r="$(docker run --rm --network $N-pub -e PGCONNECT_TIMEOUT=4 $PGIMG sh -c 'for i in $(seq 1 25); do psql "host=pg-front user=ipass_outside dbname=ipass sslmode=require password=wrong" -c "select 1" 2>&1 & done; wait' | grep -vc 'password authentication failed')"
[ "$r" -ge 5 ] && ok "wrong-password flood: $r of 25 attempts cut at the proxy before authentication" || bad "flood not throttled" "$r"

# rollback pieces
ENV_FILE="$TMP/.env" PSQL_EXEC="docker exec -i -e OUT_PW $N-pg" sh "$HERE/outside/outside-role.sh" down >/dev/null 2>&1 || bad "down" ""
r="$(A -tAc "select (select count(*) from pg_roles where rolname='ipass_outside') + (select count(*) from pg_policies where policyname='outside_pin') + (select count(*) from pg_database d where datname='ipass' and has_database_privilege('public','ipass','TEMPORARY') = false)")"
[ "$r" = "0" ] && ok "down: role gone, pin policies gone, TEMPORARY back to PUBLIC" || bad "down state" "$r"
grep -q OUTSIDE_DB_PASSWORD "$TMP/.env" && bad "password line left in .env" "" || ok "down: OUTSIDE_DB_PASSWORD removed from .env"
sleep 12; r="$(O "select 1")"; grep -qiE 'password authentication failed|server closed|does not exist' <<<"$r" && ok "after rollback the role cannot log in" || bad "login after drop" "$r"
echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"; exit $fail
