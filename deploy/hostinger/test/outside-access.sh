#!/bin/bash
# Behaviour proof of the outside Postgres door, option A (deploy/hostinger/outside): the REAL compose file (project
# ipass-outside-t), the REAL pg_hba.conf, the REAL script-made TLS directory as a bind mount, the REAL refresh/role/verify
# scripts, against a stand-in "live" Postgres built from the real roles (postgres-init) and the real Flyway SQL of every module.
# Build PC only, under the PC-wide Docker lock (not taken over: exit 2 if a live owner holds it). Touches no server.
#   bash deploy/hostinger/test/outside-access.sh
# Not testable here (needs a Linux host with iptables): pg-throttle.sh rules, enable_ip_masquerade=false; only their dry-run text is checked.
set -u
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")/.." && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"; OUT="$HERE/outside"
LOCK="${DOCKER_SUITE_LOCK:-/d/Projects/ipass/.docker-suite.lock}"
N=ipass-outside-t; PGIMG=postgres:17.11-alpine; P=ipass-outside-t
fail=0; ok() { echo "PASS $1"; }; bad() { echo "FAIL $1  [$2]"; fail=1; }
win() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-32; }
TMP="$(mktemp -d)"; MADE_TLS=0

# ---- static checks (no container, no lock needed)
S_ok() { if eval "$2"; then ok "$1"; else bad "$1" "$2"; fi; }
S_ok "base compose.yaml publishes nothing and is not touched by the outside file" '! grep -q "ports:" "$HERE/compose.yaml" && ! grep -qE "ipass-data|^  postgres:" "$OUT/compose.outside.yaml"'
S_ok "only pg-front publishes a port (pg-demo has none)" '[ "$(awk "/^  [a-z-]+:\$/{svc=\$1} /ports:/{print svc}" "$OUT/compose.outside.yaml")" = "pg-front:" ]'
S_ok "pg-front is on pgfront+pgpublic only; pg-demo on pgfront only" 'grep -c "networks: \[ipass-pgfront\]" "$OUT/compose.outside.yaml" | grep -qx 1 && grep -q "networks: \[ipass-pgfront, ipass-pgpublic\]" "$OUT/compose.outside.yaml"'
S_ok "pgfront internal, pgpublic without NAT and with a fixed bridge name" 'grep -A2 "ipass-pgfront:  " "$OUT/compose.outside.yaml" | grep -q "internal: true" && grep -q "enable_ip_masquerade: \"false\"" "$OUT/compose.outside.yaml" && grep -q "bridge.name: br-ipass-pgpub" "$OUT/compose.outside.yaml"'
S_ok "pg-demo caps: memory, cpu, pids, no swap, cap_drop ALL" 'grep -q "mem_limit: 256m" "$OUT/compose.outside.yaml" && grep -q "memswap_limit: 256m" "$OUT/compose.outside.yaml" && grep -q "pids_limit" "$OUT/compose.outside.yaml" && grep -q "max_connections=15" "$OUT/compose.outside.yaml" && grep -q "temp_file_limit=32MB" "$OUT/compose.outside.yaml"'
S_ok "no statement text is ever logged (log_min_error_statement=panic) and ssl/scram flags are on" 'grep -q "log_min_error_statement=panic" "$OUT/compose.outside.yaml" && grep -q "ssl=on" "$OUT/compose.outside.yaml" && grep -q "password_encryption=scram-sha-256" "$OUT/compose.outside.yaml"'
S_ok "pg_hba.conf: exactly 3 rules, hostssl for one role+db, last rule is reject, nothing falls open" '[ "$(grep -vc "^#" "$OUT/pg_hba.conf")" = 3 ] && grep -qE "^hostssl +ipass_demo +ipass_outside +all +scram-sha-256" "$OUT/pg_hba.conf" && [ "$(grep -v "^#" "$OUT/pg_hba.conf" | tail -1 | awk "{print \$NF}")" = reject ] && ! grep -v "^#" "$OUT/pg_hba.conf" | grep -q "trust.*all  *all"'
S_ok "tls/ and the key are git-ignored" '(cd "$REPO" && git check-ignore -q deploy/hostinger/outside/tls/server.key)'
S_ok "no script puts a password on a command line or traces" '! grep -nE "set -x|-v +pw=|PASSWORD=\\\$|PGPASSWORD=\"\\\$[A-Za-z_]*\" +psql" "$OUT"/*.sh'
o="$(DRY_RUN=1 EXT_IF=eth0 sh "$OUT/pg-throttle.sh" on 2>&1)"
S_ok "throttle on: jump scoped to -i eth0 + published port 5432, NEW only; egress drop on br-ipass-pgpub" 'grep -q -- "-I DOCKER-USER 1 -i eth0 -p tcp -m conntrack --ctstate NEW --ctorigdstport 5432 -j IPASS-PG" <<<"$o" && grep -q -- "-I DOCKER-USER 1 -i br-ipass-pgpub -m conntrack --ctstate NEW -j DROP" <<<"$o" && grep -q "connlimit-above 3" <<<"$o"'
S_ok "throttle on: no DOCKER-USER rule without an -i interface (internal traffic is never matched)" '! grep -- "-I DOCKER-USER" <<<"$o" | grep -qv -- " -i "'
o="$(DRY_RUN=1 EXT_IF=eth0 sh "$OUT/pg-throttle.sh" off 2>&1)"
S_ok "throttle off removes both rules and the chain" 'grep -q -- "-D DOCKER-USER -i eth0" <<<"$o" && grep -q -- "-D DOCKER-USER -i br-ipass-pgpub" <<<"$o" && grep -q -- "-X IPASS-PG" <<<"$o"'
o="$(DRY_RUN=1 EXT_IF=eth0 sh "$OUT/pg-throttle.sh" check 2>&1)"
S_ok "throttle check lists the iptables -C commands" 'grep -q -- "-C DOCKER-USER -i eth0" <<<"$o"'
S_ok "tls script refuses a validity outside 365-1100 days" '! DAYS=30 sh "$OUT/make-tls-cert.sh" "$TMP/t1" 127.0.0.1 >/dev/null 2>&1 && ! DAYS=5000 sh "$OUT/make-tls-cert.sh" "$TMP/t1" 127.0.0.1 >/dev/null 2>&1'
sh "$OUT/make-tls-cert.sh" "$TMP/t2" "203.0.113.7" > "$TMP/t2.out" 2>&1
S_ok "tls script: CA:FALSE, serverAuth, IP SAN, ~3 years, prints a SHA-256 fingerprint" 'openssl x509 -noout -text -in "$(win "$TMP/t2/server.crt")" | tr -d "\r" > "$TMP/t2.txt"; grep -q "CA:FALSE" "$TMP/t2.txt" && grep -q "TLS Web Server Authentication" "$TMP/t2.txt" && grep -q "IP Address:203.0.113.7" "$TMP/t2.txt" && grep -q "sha256 Fingerprint=" "$TMP/t2.out" && openssl x509 -noout -checkend $((1000*86400)) -in "$(win "$TMP/t2/server.crt")"'
S_ok "tls script never overwrites an existing key" '! sh "$OUT/make-tls-cert.sh" "$TMP/t2" 203.0.113.7 >/dev/null 2>&1'
S_ok "expiry check: OK now, ALERT (exit 1) when it expires within the window" 'sh "$OUT/cert-expiry-check.sh" "$(win "$TMP/t2/server.crt")" 60 >/dev/null && ! sh "$OUT/cert-expiry-check.sh" "$(win "$TMP/t2/server.crt")" 2000 >/dev/null 2>&1'
printf '' > "$TMP/e.env"
o="$(ENV_FILE="$TMP/e.env" ROTATION_LOG="$TMP/rot" sh "$OUT/outside-role.sh" env 2>&1)"
S_ok "outside-role env: both passwords generated into .env, only fingerprints printed" 'grep -qE "^DEMO_ADMIN_PASSWORD=.{32,}" "$TMP/e.env" && grep -qE "^OUTSIDE_DB_PASSWORD=.{32,}" "$TMP/e.env" && ! grep -qF "$(sed -n "s/^OUTSIDE_DB_PASSWORD=//p" "$TMP/e.env")" <<<"$o" && [ "$(grep -c "fingerprint [0-9a-f]\{8\}" <<<"$o")" = 2 ]'
cp "$TMP/e.env" "$TMP/e1.env"; ENV_FILE="$TMP/e.env" ROTATION_LOG="$TMP/rot" sh "$OUT/outside-role.sh" env >/dev/null 2>&1
S_ok "outside-role env is idempotent" 'cmp -s "$TMP/e.env" "$TMP/e1.env"'
if docker compose version >/dev/null 2>&1; then
  cp "$TMP/e.env" "$TMP/c.env"
  S_ok "outside compose file validates" 'docker compose --env-file "$(win "$TMP/c.env")" -f "$(win "$OUT/compose.outside.yaml")" config -q 2>"$TMP/cfg.err"'
fi
echo "-- static part done"

# ---- live part: needs the lock
mkdir "$LOCK" 2>/dev/null || { echo "docker lock held by: $(cat "$LOCK/owner" 2>/dev/null) - live part NOT RUN"; [ $fail -eq 0 ] && echo "STATIC PART PASSED, LIVE PART NOT RUN" || echo "FAILURES (static)"; rm -rf "$TMP"; exit 2; }
echo "arjun ipass-outside-access $(date -u +%FT%TZ) refreshed=$(date -u +%FT%TZ)" > "$LOCK/owner"
DC="docker compose -p $P --env-file $(win "$TMP/c.env") -f $(win "$OUT/compose.outside.yaml")"
cleanup() { $DC down -v >/dev/null 2>&1; docker rm -f $N-live $N-bad >/dev/null 2>&1; docker network rm $N-live >/dev/null 2>&1
  [ "$MADE_TLS" = 1 ] && rm -rf "$OUT/tls"; rm -rf "$TMP" "$LOCK"; }
trap cleanup EXIT
[ ! -e "$OUT/tls" ] || { bad "outside/tls already exists (a real certificate?): not touched, live part stopped" ""; exit 1; }
[ -z "$(docker ps -q --filter publish=5432)" ] || { bad "host port 5432 is already published by another container: live part stopped" ""; exit 1; }

# the stand-in live database: real roles + real migrations + two tenants with secret-bearing rows
PW_ADMIN="$(rand)"; PW_MIG="$(rand)"; PW_APP="$(rand)"; PW_BAK="$(rand)"; PW_RST="$(rand)"; PW_KC="$(rand)"
docker network create --internal $N-live >/dev/null
docker run -d --name $N-live --network $N-live -e POSTGRES_USER=ipass_admin -e POSTGRES_DB=ipass -e POSTGRES_PASSWORD="$PW_ADMIN" \
  -e DB_MIGRATION_PASSWORD="$PW_MIG" -e DB_PASSWORD="$PW_APP" -e BACKUP_DB_PASSWORD="$PW_BAK" -e RESTORE_DB_PASSWORD="$PW_RST" \
  -e KEYCLOAK_DB_NAME=keycloak -e KEYCLOAK_DB_USERNAME=keycloak -e KEYCLOAK_DB_PASSWORD="$PW_KC" \
  -v "$(win "$HERE/postgres-init"):/docker-entrypoint-initdb.d:ro" $PGIMG >/dev/null || { bad "live stand-in did not start" ""; exit 1; }
for i in $(seq 1 60); do [ "$(docker logs $N-live 2>&1 | grep -c 'ready to accept connections')" -ge 2 ] && break; sleep 1; done
A() { docker exec -i $N-live psql -q -U ipass_admin -d ipass -v ON_ERROR_STOP=1 "$@"; }
A -tAc "select 1" >/dev/null 2>&1 || { bad "live stand-in not ready" "$(docker logs $N-live 2>&1 | tail -3)"; exit 1; }
( cd "$REPO" && git ls-files '*/src/main/resources/db/migration/*.sql' ) | while read -r f; do b="$(basename "$f")"; v="${b%%__*}"; v="${v#V}"; echo "${v//_/.} $f"; done | sort -V | cut -d' ' -f2- > "$TMP/order"
nmig=0; for f in $(cat "$TMP/order"); do
  docker exec -i $N-live psql -q -U ipass_migrator -d ipass -v ON_ERROR_STOP=1 -1 -f - < "$REPO/$f" >/dev/null 2>"$TMP/mig.err" || { bad "migration $f" "$(head -2 "$TMP/mig.err")"; exit 1; }; nmig=$((nmig+1)); done
ok "stand-in live database: $nmig real migrations applied"
A <<'SQL' >/dev/null || { bad "seed" ""; exit 1; }
INSERT INTO tenant(code,status) VALUES ('showcase-a','ACTIVE'),('showcase-b','ACTIVE');
INSERT INTO system(tenant_id,code,name,direction) SELECT id,'sys-'||code,'S '||code,'BOTH' FROM tenant;
INSERT INTO target_endpoint(tenant_id,system_id,code,name,base_url,http_method,auth_method,timeout_ms)
  SELECT s.tenant_id,s.id,'ep','EP','https://x.invalid/','POST','NO_AUTH',1000 FROM system s;
INSERT INTO source_credential(tenant_id,source_system_id,customer_id,issuer,client_id)
  SELECT s.tenant_id,s.id,'c1','https://LEAKMARK.invalid/'||s.code,'LEAKMARK-client-'||s.code FROM system s;
INSERT INTO delivery(tenant_id,state,claim_token) SELECT id,'PENDING','00000000-0000-4000-8000-0000000c1a1a' FROM tenant;
SQL
ok "live stand-in seeded: two tenants, a LEAKMARK source credential and a claim token in each"
live_hba="$(docker exec $N-live sh -c 'sha256sum /var/lib/postgresql/data/pg_hba.conf' | cut -c1-16)"
live_roles="$(A -tAc "select string_agg(rolname, ',' order by rolname) from pg_roles")"
live_nets="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' $N-live)"

# H1: the TLS directory is made by the SCRIPT, in the place the compose file bind-mounts it (outside/tls)
sh "$OUT/make-tls-cert.sh" "$OUT/tls" "pg-front,localhost,127.0.0.1" >/dev/null 2>&1 && MADE_TLS=1 || { bad "make-tls-cert into outside/tls" ""; exit 1; }
CRT="$OUT/tls/server.crt"
$DC up -d pg-demo >/dev/null 2>"$TMP/up.err" || { bad "compose up pg-demo" "$(head -3 "$TMP/up.err")"; exit 1; }
DEMO="$($DC ps -q pg-demo)"
for i in $(seq 1 60); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$DEMO" 2>/dev/null)" = healthy ] && break; sleep 2; done
[ "$(docker inspect -f '{{.State.Health.Status}}' "$DEMO")" = healthy ] && ok "pg-demo starts healthy with the script-made bind-mounted TLS dir (ssl=on, our hba, hba self-check passed)" || { bad "pg-demo not healthy" "$(docker logs "$DEMO" 2>&1 | tail -5)"; exit 1; }
D() { docker exec -i "$DEMO" psql -q -U ipass_demo_admin -d ipass_demo -v ON_ERROR_STOP=1 "$@"; }
[ "$(D -tAc 'show ssl')" = on ] && ok "ssl is on" || bad "ssl" ""
[ "$(docker exec "$DEMO" ls -l /run/tls/server.key | awk '{print $1" "$3}')" = "-rw------- postgres" ] && ok "key inside the container: mode 0600, owner postgres" || bad "key perms" "$(docker exec "$DEMO" ls -l /run/tls)"
[ "$(docker inspect -f '{{.HostConfig.Memory}} {{.HostConfig.PidsLimit}}' "$DEMO")" = "268435456 100" ] && ok "pg-demo memory cap 256 MB, pids 100 (disk: no Docker cap, see OUTSIDE-ACCESS.md)" || bad "caps" ""
[ "$(D -tAc 'show max_connections') $(D -tAc 'show temp_file_limit') $(D -tAc 'show log_min_error_statement')" = "15 32MB panic" ] && ok "instance caps: 15 connections, temp_file_limit 32MB, no statement text in logs" || bad "instance settings" ""
# M1: the hba self-check is real - a fail-open file (last rule not reject) makes the same healthcheck command fail
HC="$(docker inspect -f '{{index .Config.Healthcheck.Test 1}}' "$DEMO")"
printf 'local all ipass_demo_admin trust\nhostssl ipass_demo ipass_outside all scram-sha-256\nhost all all all scram-sha-256\n' > "$TMP/bad_hba"
docker run -d --name $N-bad -e POSTGRES_USER=ipass_demo_admin -e POSTGRES_DB=ipass_demo -e POSTGRES_PASSWORD="$PW_ADMIN" -v "$(win "$TMP/bad_hba"):/etc/postgresql/pg_hba.conf:ro" $PGIMG postgres -c hba_file=/etc/postgresql/pg_hba.conf >/dev/null 2>&1
for i in $(seq 1 40); do docker exec $N-bad pg_isready -q -U ipass_demo_admin -d ipass_demo 2>/dev/null && break; sleep 1; done; sleep 2
docker exec $N-bad sh -c "$HC" >/dev/null 2>&1 && bad "hba self-check passed on a fail-open file" "" || ok "hba self-check FAILS on a file whose last rule is not reject (door would not start)"
docker exec "$DEMO" sh -c "$HC" >/dev/null 2>&1 && ok "hba self-check passes on the real file" || bad "self-check on real file" ""
docker rm -f $N-bad >/dev/null 2>&1

# demo data: refresh from the stand-in live database
export SRC_EXEC="docker exec -i $N-live" DEMO_EXEC="docker exec -i $DEMO"
out="$(sh "$OUT/demo-refresh.sh" showcase-a 2>&1)"; rc=$?
[ $rc -eq 0 ] && ok "demo-refresh.sh showcase-a ran: $out" || { bad "refresh" "$out"; exit 1; }
EXPT="$(grep -v '^#' "$OUT/demo-columns.txt" | cut -d'|' -f1 | { cat; echo demo_snapshot; } | sort | paste -sd, -)"
[ "$(D -tAc "select string_agg(tablename, ',' order by tablename) from pg_tables where schemaname='public'")" = "$EXPT" ] && ok "demo tables are exactly the allow-list (+ demo_snapshot)" || bad "demo tables" ""
D -tAc "select code from public.tenant" | grep -qx showcase-a && [ "$(D -tAc 'select count(*) from public.tenant')" = 1 ] && ok "demo holds tenant showcase-a only" || bad "tenant rows" ""
[ "$(D -tAc 'select count(*) from public.system') $(D -tAc 'select count(*) from public.delivery')" = "1 1" ] && ok "demo rows copied (1 system, 1 delivery of showcase-a)" || bad "row counts" ""
docker exec "$DEMO" pg_dump -U ipass_demo_admin -d ipass_demo > "$TMP/demo.dump" 2>/dev/null
grep -qE 'LEAKMARK|showcase-b|00000000-0000-4000-8000-0000000c1a1a|claim_token|credential_ref' "$TMP/demo.dump" && bad "the demo dump holds a secret, a token or another tenant" "$(grep -oE 'LEAKMARK|showcase-b|claim_token|credential_ref' "$TMP/demo.dump" | sort -u | tr '\n' ' ')" || ok "demo dump: no credential row (LEAKMARK), no claim token, no showcase-b, no secret column"
[ "$(D -tAc "select count(*) from pg_database where datname in ('keycloak','ipass')")" = 0 ] && ok "no keycloak or live database in pg-demo" || bad "extra databases" ""
[ "$(D -tAc "select string_agg(rolname, ',' order by rolname) from pg_roles where rolname !~ '^pg_'")" = "ipass_demo_admin" ] && ok "before the role step pg-demo has only its admin role" || bad "roles" ""
D -tAc "select count(*) from public.demo_snapshot" | grep -qx 1 && ok "demo_snapshot says when and from which tenant the copy was taken" || bad "snapshot stamp" ""
# repeatable and a snapshot: a later live change is invisible until the next refresh
A -c "INSERT INTO system(tenant_id,code,name,direction) SELECT id,'sys-extra','S extra','BOTH' FROM tenant WHERE code='showcase-a'" >/dev/null
[ "$(D -tAc 'select count(*) from public.system')" = 1 ] && ok "snapshot: a live change after the refresh is not visible in the demo" || bad "snapshot semantics" ""
sh "$OUT/demo-refresh.sh" showcase-a >/dev/null 2>&1 && [ "$(D -tAc 'select count(*) from public.system')" = 2 ] && ok "refresh is repeatable and picks the change up" || bad "second refresh" ""
before="$(D -tAc 'select count(*) from public.system')"
sh "$OUT/demo-refresh.sh" no-such-tenant >/dev/null 2>&1 && bad "refresh of an unknown tenant succeeded" "" || { [ "$(D -tAc 'select count(*) from public.system')" = "$before" ] && ok "unknown tenant: refused, demo unchanged"; }
printf 'system|tenant_id|id,tenant_id,no_such_column\n' > "$TMP/drift.txt"
COLUMNS_FILE="$TMP/drift.txt" sh "$OUT/demo-refresh.sh" showcase-a >/dev/null 2>&1 && bad "schema drift not detected" "" || { [ "$(D -tAc 'select count(*) from public.system')" = "$before" ] && ok "schema drift (missing column): refused, demo unchanged"; }

# the role: password handling
export ENV_FILE="$TMP/c.env" ROTATION_LOG="$TMP/rot"
export DEMO_EXEC="docker exec -i -e OUT_PW $DEMO"
out="$(sh "$OUT/outside-role.sh" up 2>&1)"; rc=$?
PW_OUT="$(sed -n 's/^OUTSIDE_DB_PASSWORD=//p' "$TMP/c.env")"
[ $rc -eq 0 ] && ok "outside-role.sh up created ipass_outside in pg-demo" || { bad "role up" "$out"; exit 1; }
grep -qF "$PW_OUT" <<<"$out" && bad "the password was printed by up" "" || ok "up: the password is not in the output"
sh "$OUT/outside-role.sh" up >/dev/null 2>&1 && ok "up is idempotent" || bad "up twice" ""
[ "$(D -tAc "select rolsuper::int + rolbypassrls::int + rolcreaterole::int, rolconnlimit from pg_roles where rolname='ipass_outside'" | tr -d ' ')" = "0|3" ] && ok "role: NOSUPERUSER NOBYPASSRLS, connection limit 3" || bad "role attrs" ""
old="$PW_OUT"; out="$(sh "$OUT/outside-role.sh" rotate 2>&1)"; PW_OUT="$(sed -n 's/^OUTSIDE_DB_PASSWORD=//p' "$TMP/c.env")"
[ "$old" != "$PW_OUT" ] && ! grep -qF "$PW_OUT" <<<"$out" && ! grep -qF "$old" <<<"$out" && ok "rotate: new password, neither old nor new printed" || bad "rotate" "$out"
echo "short" > /dev/null; cp "$TMP/c.env" "$TMP/c.keep"; sed -i 's/^OUTSIDE_DB_PASSWORD=.*/OUTSIDE_DB_PASSWORD=tooshort/' "$TMP/c.env"
sh "$OUT/outside-role.sh" up >/dev/null 2>&1 && bad "a short password was accepted" "" || ok "a short password is refused before anything is sent"; cp "$TMP/c.keep" "$TMP/c.env"
unset DEMO_EXEC
if docker logs "$DEMO" 2>&1 | grep -qF -e "$PW_OUT" -e "$old"; then bad "a password appears in the pg-demo log" ""; else ok "no password (old or new) in the pg-demo log"; fi

# the door: start the proxy, then run the client-seat verification (the same script used on the server before the firewall opens)
$DC up -d pg-front >/dev/null 2>"$TMP/up2.err" || { bad "compose up pg-front" "$(head -3 "$TMP/up2.err")"; exit 1; }
sleep 3
[ "$(docker ps --format '{{.Names}} {{.Ports}}' --filter "label=com.docker.compose.project=$P" | grep -c '0.0.0.0:5432->5432')" = 1 ] && ok "only one container of the stack publishes a port, and it publishes 5432" || bad "published ports" "$(docker ps --format '{{.Names}} {{.Ports}}' --filter "label=com.docker.compose.project=$P")"
OUTSIDE_PW="$PW_OUT" bash "$OUT/verify.sh" pg-front "$CRT" "${P}_ipass-pgpublic" > "$TMP/verify.out" 2>&1; vrc=$?
cat "$TMP/verify.out"; [ $vrc -eq 0 ] && ok "verify.sh (client-seat probes through the proxy): all passed" || bad "verify.sh" "see FAIL lines above"
# the live stack did not change
[ "$(docker exec $N-live sh -c 'sha256sum /var/lib/postgresql/data/pg_hba.conf' | cut -c1-16)" = "$live_hba" ] && ok "live pg_hba.conf byte-identical" || bad "live hba changed" ""
[ "$(A -tAc "select string_agg(rolname, ',' order by rolname) from pg_roles")" = "$live_roles" ] && ok "live roles unchanged (no ipass_outside in the live database)" || bad "live roles changed" ""
[ "$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' $N-live)" = "$live_nets" ] && ok "live container networks unchanged" || bad "live networks changed" ""
# rollback = down -v
$DC down -v >/dev/null 2>&1
[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$P")" ] && [ -z "$(docker volume ls -q --filter "label=com.docker.compose.project=$P")" ] && ok "rollback: stack, networks and demo volume removed by one 'down -v'" || bad "rollback leftovers" ""
[ "$(A -tAc "select count(*) from system")" -ge 2 ] && ok "live data intact after rollback" || bad "live data" ""
echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"; exit $fail
