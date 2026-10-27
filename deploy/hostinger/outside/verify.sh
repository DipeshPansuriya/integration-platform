#!/bin/sh
# Verification of the outside door FROM A CLIENT'S SEAT. Run it on the server after pg-front is up and BEFORE the provider
# firewall is opened (host = 127.0.0.1, network = host), and again from outside after (OUTSIDE-ACCESS.md section 7). The test
# (test/outside-access.sh) runs this same script against a throwaway stack. Read-only probes only; it needs the outside password.
#   OUTSIDE_PW=... ./outside/verify.sh <host> <server.crt> [docker-network]        e.g. 127.0.0.1 ./outside/tls/server.crt host
# The password is taken from the environment (never an argument) and handed to the client container by NAME (`-e PGPASSWORD`),
# so it is in no process's command line.
# Exit 0 only if every probe passes. Needs docker and the postgres image already present (no pull is forced).
set -u
export MSYS_NO_PATHCONV=1
HOST="${1:?usage: OUTSIDE_PW=... verify.sh <host> <server.crt> [docker-network]}"; CRT="${2:?server.crt}"; NET="${3:-host}"
: "${OUTSIDE_PW:?set OUTSIDE_PW in the environment}"
HERE="$(cd "$(dirname "$0")" && pwd)"; PORT="${PG_PORT:-5432}"; IMG="${PG_CLIENT_IMAGE:-postgres:17.11-alpine}"
CA="$(cat "$CRT")"; fail=0
ok() { echo "PASS $1"; }; bad() { echo "FAIL $1  [$2]"; fail=1; }
# c <user> <password-env-name> <db> <sslmode> <sql>  -> output of psql (stdout+stderr); the password never appears in argv
c() { PW="$( [ "$2" = OUTSIDE_PW ] && printf '%s' "$OUTSIDE_PW" || printf '%s' "$2" )"
  PGPASSWORD="$PW" docker run --rm --network "$NET" -e CA="$CA" -e PGPASSWORD -e PGCONNECT_TIMEOUT=6 -e SQL="$5" "$IMG" sh -c \
   "printf '%s\n' \"\$CA\" >/tmp/ca.crt; psql \"host=$HOST port=$PORT user=$1 dbname=$3 sslmode=$4 sslrootcert=/tmp/ca.crt\" -tA -c \"\$SQL\" 2>&1"; }
O() { c ipass_outside OUTSIDE_PW "${2:-ipass_demo}" "${3:-verify-full}" "$1"; }   # the outside role, TLS verified against the pinned cert
want() { # name regex output
  if printf '%s' "$3" | grep -qiE "$2"; then ok "$1"; else bad "$1" "$(printf '%s' "$3" | head -c 200)"; fi; }
REJ='rejects connection|no pg_hba.conf entry'
want "TLS login (verify-full against the pinned cert) as ipass_outside" '^ipass_outside\|t$' "$(O "select current_user, ssl from pg_stat_ssl where pid = pg_backend_pid()")"
want "TLS 1.2 or 1.3" '^TLSv1\.[23]$' "$(O "select version from pg_stat_ssl where pid = pg_backend_pid()")"
want "plain (non-TLS) connection refused" "$REJ" "$(O "select 1" ipass_demo disable)"
want "wrong password refused" 'password authentication failed' "$(c ipass_outside "wrong-$OUTSIDE_PW" ipass_demo verify-full "select 1")"
for u in ipass_demo_admin ipass_admin ipass_backup ipass_app ipass_migrator keycloak postgres; do sleep 1
  want "role $u refused before any password check" "$REJ" "$(c "$u" dummy-not-a-password ipass_demo verify-full "select 1")"; done
for d in ipass keycloak postgres template1; do sleep 1; want "ipass_outside refused on database $d" "$REJ" "$(O "select 1" "$d")"; done
# what exists: exactly the allow-listed tables (plus the snapshot stamp); nothing secret can be there
EXP="$(grep -v '^#' "$HERE/demo-columns.txt" | cut -d'|' -f1 | { cat; echo demo_snapshot; } | sort | paste -sd, -)"
want "tables are exactly the allow-list" "^$EXP\$" "$(O "select string_agg(tablename, ',' order by tablename) from pg_tables where schemaname = 'public'")"
for t in source_credential target_credential app_user role_grant audit_event payload outbox; do want "no table $t in the demo" 'does not exist' "$(O "select * from $t")"; done
want "snapshot stamp readable" '^[0-9]{4}-' "$(O "select taken_at from demo_snapshot")"
want "no secret-looking column anywhere" '^0$' "$(O "select count(*) from information_schema.columns where table_schema = 'public' and column_name ~* '(secret|token|password|credential|hash|settings|payload|detail)'")"
for t in "insert into system(id) values (gen_random_uuid())" "update system set name = 'x'" "delete from system" "truncate system" "create table t(i int)" "create temp table t(i int)" "create schema s" "drop table system"; do
  want "refused even with read-only switched off: $t" 'permission denied|must be owner' "$(O "begin read write; $t")"; done
# abuse classes (review H3)
want "advisory locks not callable" 'permission denied' "$(O "select pg_advisory_lock(1)")"
want "try-advisory locks not callable" 'permission denied' "$(O "select pg_try_advisory_lock(1)")"
want "large objects not creatable" 'permission denied' "$(O "select lo_create(0)")"
want "lowrite not callable" 'permission denied' "$(O "select lowrite(0, 'x'::bytea)")"
want "temp_file_limit cannot be lifted" 'permission denied' "$(O "set temp_file_limit = '1GB'")"
want "work_mem default is 2MB" '^2MB$' "$(O "show work_mem")"
echo "INFO  work_mem can be raised by the client (SET work_mem): bounded by the container's 256 MB cap, not by Postgres"
want "statement_timeout cancels a long query" 'statement timeout' "$(O "select pg_sleep(20)")"
want "a big sort is stopped (temp_file_limit or statement timeout)" 'temporary file size exceeds|statement timeout' "$(O "set work_mem = '64kB'; select g::text from generate_series(1,6000000) g order by 1 desc limit 1")"
# limits: 3 held sessions fill the role limit, the 4th is refused; a wrong-password flood is cut at the proxy
PGPASSWORD="$OUTSIDE_PW" docker run -d --rm --name ipass-verify-hold --network "$NET" -e CA="$CA" -e PGPASSWORD "$IMG" sh -c \
  "printf '%s\n' \"\$CA\" >/tmp/ca.crt; for i in 1 2 3; do psql \"host=$HOST port=$PORT user=ipass_outside dbname=ipass_demo sslmode=verify-full sslrootcert=/tmp/ca.crt\" -c 'select pg_sleep(12)' >/dev/null 2>&1 & done; wait" >/dev/null 2>&1
sleep 4; want "4th simultaneous connection refused (role limit 3 / proxy 3 per address)" 'too many connections|server closed|connection (refused|reset)|timeout|could not' "$(O "select 1")"
docker rm -f ipass-verify-hold >/dev/null 2>&1; sleep 13
n="$(docker run --rm --network "$NET" -e PGCONNECT_TIMEOUT=4 "$IMG" sh -c "for i in \$(seq 1 25); do psql \"host=$HOST port=$PORT user=ipass_outside dbname=ipass_demo sslmode=require password=wrong\" -c 'select 1' 2>&1 & done; wait" | grep -vc 'password authentication failed')"
[ "$n" -ge 5 ] && ok "wrong-password flood: $n of 25 attempts cut at the proxy before authentication" || bad "flood not throttled" "$n"
[ $fail -eq 0 ] && echo "VERIFY: ALL PASSED" || echo "VERIFY: FAILURES"; exit $fail
