#!/bin/bash
# Offline tests for deploy/hostinger (no container start, no lock needed): script dry-runs, secret hygiene and the
# static controls of compose.yaml (ADR-H-IPASS-01 conformance). Run:  bash deploy/hostinger/test/test-scripts.sh
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
fail=0
ok() { echo "PASS $1"; }
bad() { echo "FAIL $1"; fail=1; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# --- bootstrap-env.sh
names="$(DRY_RUN=1 sh "$HERE/bootstrap-env.sh")"
check "bootstrap lists 14 secret names" '[ "$(echo "$names" | wc -l)" -eq 14 ]'
out="$(IPASS_DOMAIN=example.test IMAGE_TAG=abc1234 sh "$HERE/bootstrap-env.sh" "$TMP/.env" 2>&1)"
check "bootstrap writes the env file" '[ -s "$TMP/.env" ]'
check "bootstrap prints fingerprints, never a value" '! grep -q "=" <<<"$(echo "$out" | grep fingerprint)" && [ "$(echo "$out" | grep -c fingerprint)" -eq 15 ]'
leak=0; while IFS='=' read -r k v; do case "$k" in *PASSWORD|*SECRET|*KEK_K1) [ -n "$v" ] && grep -qF -- "$v" <<<"$out" && leak=1;; esac; done < "$TMP/.env"
check "no generated value appears in the output" '[ $leak -eq 0 ]'
check "every secret name is in env.example" 'for n in $names; do grep -q "^$n=" "$HERE/env.example" || exit 1; done'
check "env.example holds no value" '! grep -qE "^[A-Z_0-9]+=." "$HERE/env.example"'
check "env file has every secret non-empty" 'for n in $names; do grep -qE "^$n=.{16,}" "$TMP/.env" || exit 1; done'
check "secrets differ per name" '[ "$(grep -E "PASSWORD|SECRET" "$TMP/.env" | cut -d= -f2 | sort -u | wc -l)" -eq 13 ]'
check "bootstrap refuses to overwrite" '! IPASS_DOMAIN=example.test IMAGE_TAG=abc1234 sh "$HERE/bootstrap-env.sh" "$TMP/.env" >/dev/null 2>&1'
check "bootstrap refuses to overwrite even with FORCE=1 (no blanket rotation)" '! FORCE=1 IPASS_DOMAIN=example.test IMAGE_TAG=abc1234 sh "$HERE/bootstrap-env.sh" "$TMP/.env" >/dev/null 2>&1'
check "the Keycloak bootstrap admin is in its own file, not in .env, compose.yaml or env.example" '[ -s "$TMP/.env.bootstrap-admin" ] && grep -q "^KC_BOOTSTRAP_ADMIN_PASSWORD=.\{16,\}" "$TMP/.env.bootstrap-admin" && ! grep -q KC_BOOTSTRAP "$TMP/.env" && ! grep -q KC_BOOTSTRAP "$HERE/compose.yaml" && ! grep -q "^KC_BOOTSTRAP" "$HERE/env.example"'
check "bootstrap admin value never appears in the output" 'v="$(grep ^KC_BOOTSTRAP_ADMIN_PASSWORD= "$TMP/.env.bootstrap-admin" | cut -d= -f2)"; [ -n "$v" ] && ! grep -qF -- "$v" <<<"$out"'
check "bootstrap-admin override injects the file, holds no value" 'grep -q "env_file" "$HERE/keycloak/compose.bootstrap-admin.yaml" && ! grep -q "KC_BOOTSTRAP" "$HERE/keycloak/compose.bootstrap-admin.yaml"'
check "retire script needs a different named admin and prints its steps" '! KC_ADMIN_USER=bootstrap-admin DRY_RUN=1 sh "$HERE/keycloak/retire-bootstrap-admin.sh" >/dev/null 2>&1 && KC_ADMIN_USER=alice DRY_RUN=1 sh "$HERE/keycloak/retire-bootstrap-admin.sh" | grep -q "delete user bootstrap-admin"'
check "seed script works with the bootstrap admin retired (named admin)" 'grep -q KC_ADMIN_USER "$HERE/seed/seed-showcase.sh"'
check "no ipass compose file mounts a Docker socket" '! grep -rqi "docker.sock" "$HERE/compose.yaml" "$HERE/demo" "$HERE/keycloak" "$HERE/test/compose.smoke.yaml"'
ROTATE_SOURCE_ONLY=1 . "$HERE/rotate-secret.sh"; set +e
printf 'A=1\nDB_PASSWORD=old\nB=2\n' > "$TMP/r.env"; chmod 644 "$TMP/r.env"
set_env_value "$TMP/r.env" DB_PASSWORD 'new+/=value'
check "rotate: set_env_value replaces only that line, atomically, mode 600" '[ "$(cat "$TMP/r.env")" = "$(printf "A=1\nDB_PASSWORD=new+/=value\nB=2")" ] && { [ "$(uname -o 2>/dev/null)" = Msys ] || [ "$(stat -c %a "$TMP/r.env")" = 600 ]; }'
check "rotate: a name missing from the env file is an error" '! set_env_value "$TMP/r.env" NOPE x 2>/dev/null'
for n in DB_PASSWORD DB_MIGRATION_PASSWORD BACKUP_DB_PASSWORD RESTORE_DB_PASSWORD KEYCLOAK_DB_PASSWORD POSTGRES_PASSWORD CONSOLE_OIDC_CLIENT_SECRET PLATFORM_ADMIN_CLIENT_SECRET OPERATOR_AGENT_CLIENT_SECRET; do
  check "rotate: $n has a plan" 'ENV_FILE="$TMP/r.env" DRY_RUN=1 sh "$HERE/rotate-secret.sh" "$n" | grep -q "would generate"'
done
check "rotate: KEK, showcase and unknown names are refused with a reason" '! sh "$HERE/rotate-secret.sh" PLATFORM_CREDENTIAL_KEK_K1 2>/dev/null && ! sh "$HERE/rotate-secret.sh" SHOWCASE_PASSWORD_ADMIN_A 2>/dev/null && ! sh "$HERE/rotate-secret.sh" HOME 2>/dev/null'
# docker compose lets the PROCESS environment beat --env-file: a rotate script that exports the old .env would recreate the app with the OLD password (found in the smoke, row 394)
cat > "$TMP/fakecompose" <<'FAKE'
#!/bin/sh
case "$*" in *" up "*|up\ *) echo "${DB_PASSWORD:-unset-in-env}" > "$FAKE_SEEN";; *) cat >/dev/null;; esac
FAKE
chmod +x "$TMP/fakecompose"
printf 'POSTGRES_USER=ipass_admin
DB_PASSWORD=oldoldoldoldoldold
' > "$TMP/r2.env"
( unset DB_PASSWORD; export FAKE_SEEN="$TMP/up.seen"; COMPOSE="$TMP/fakecompose" ENV_FILE="$TMP/r2.env" ROTATION_LOG="$TMP/r2.log" sh "$HERE/rotate-secret.sh" DB_PASSWORD >/dev/null 2>&1 )
check "rotate: the re-created service sees the NEW value (the script does not export the old .env)" '[ -s "$TMP/up.seen" ] && [ "$(cat "$TMP/up.seen")" != oldoldoldoldoldold ] && ! grep -q "set -a" "$HERE/rotate-secret.sh"'
check "rotate: .env holds the new value and the log only a fingerprint" '! grep -q oldoldoldold "$TMP/r2.env" && grep -q "DB_PASSWORD fingerprint" "$TMP/r2.log" && ! grep -qF "$(grep ^DB_PASSWORD= "$TMP/r2.env" | cut -d= -f2)" "$TMP/r2.log"'
check "rotate: dry run changes nothing" 'before="$(cat "$TMP/r.env")"; ENV_FILE="$TMP/r.env" DRY_RUN=1 sh "$HERE/rotate-secret.sh" DB_PASSWORD >/dev/null; [ "$before" = "$(cat "$TMP/r.env")" ]'
check "bootstrap refuses tag latest" '! IPASS_DOMAIN=example.test IMAGE_TAG=latest sh "$HERE/bootstrap-env.sh" "$TMP/x.env" >/dev/null 2>&1'
check "bootstrap refuses a bad domain" '! IPASS_DOMAIN="a b;rm" IMAGE_TAG=abc sh "$HERE/bootstrap-env.sh" "$TMP/y.env" >/dev/null 2>&1'
if [ "$(uname -o 2>/dev/null)" != Msys ]; then check "env file mode 600" '[ "$(stat -c %a "$TMP/.env")" = 600 ]'; fi

# --- kafka topics = the code's register
code_topics="$(grep -A1 'PLATFORM_TOPICS = List.of' "$REPO/platform-config/src/main/java/io/integrationplatform/config/destination/SystemTopicRefusal.java" | grep -oE '"platform\.[a-z.]+"' | tr -d '"' | sort)"
script_topics="$(DRY_RUN=1 sh "$HERE/kafka/create-topics.sh" | grep -oE -- '--topic [^ ]+' | cut -d' ' -f2 | sort)"
check "kafka-init topics equal SystemTopicRefusal.PLATFORM_TOPICS" '[ -n "$code_topics" ] && [ "$code_topics" = "$script_topics" ]'
check "kafka-init is idempotent (--if-not-exists)" 'DRY_RUN=1 sh "$HERE/kafka/create-topics.sh" | grep -c -- --if-not-exists | grep -q 3'

# --- backup / restore
BACKUP_SOURCE_ONLY=1 . "$HERE/backup/backup.sh"; set +e   # backup.sh sets -e for itself
check "backup waits 3600s at 01:00 UTC" '[ "$(seconds_until_run 3600)" -eq 3600 ]'
check "backup waits a full day at 02:00:00" '[ "$(seconds_until_run 7200)" -eq 86400 ]'
check "backup waits 82800s at 03:00 UTC" '[ "$(seconds_until_run 10800)" -eq 82800 ]'
bout="$(DRY_RUN=1 RUN_ONCE=1 BACKUP_DB_USERNAME=ipass_backup sh "$HERE/backup/backup.sh")"
check "backup dumps ipass as ipass_backup and keycloak, -Fc" 'grep -q "pg_dump -Fc .* -U ipass_backup .* ipass$" <<<"$bout" && grep -q "pg_dump -Fc .* keycloak$" <<<"$bout"'
mkdir -p "$TMP/bk"; : > "$TMP/bk/ipass-old.dump"; : > "$TMP/bk/ipass-new.dump"; touch -d '10 days ago' "$TMP/bk/ipass-old.dump"
BACKUP_DIR="$TMP/bk" prune
check "prune removes a dump older than 7 days, keeps a new one" '[ ! -e "$TMP/bk/ipass-old.dump" ] && [ -e "$TMP/bk/ipass-new.dump" ]'
check "backup dumps keycloak as ipass_backup too (one credential in pg-backup)" 'grep -q "pg_dump -Fc .* -U ipass_backup .* keycloak$" <<<"$bout"'
# stubs: pg_dump writes a fake archive (or a truncated one / fails), pg_restore -l accepts only the fake archive
mkdir -p "$TMP/stub"
cat > "$TMP/stub/pg_dump" <<'STUB'
#!/bin/sh
out=""; prev=""; for a in "$@"; do [ "$prev" = -f ] && out="$a"; prev="$a"; last="$a"; done
case "${STUB_MODE:-ok}:$last" in
  fail:"${STUB_DB:-}") echo partial > "$out"; exit 1;;
  corrupt:"${STUB_DB:-}") echo garbage > "$out"; exit 0;;
esac
echo PGDMP > "$out"
STUB
cat > "$TMP/stub/pg_restore" <<'STUB'
#!/bin/sh
for f; do :; done
grep -q PGDMP "$f"
STUB
chmod +x "$TMP/stub/pg_dump" "$TMP/stub/pg_restore"
run_backup() { # mode db dir -> status of backup_all
  ( export PATH="$TMP/stub:$PATH" BACKUP_DIR="$3" STUB_MODE="$1" STUB_DB="$2"; backup_all >/dev/null 2>&1 )
}
mkdir -p "$TMP/b-ok"; run_backup ok "" "$TMP/b-ok"; st_ok=$?
check "backup_all keeps two verified dumps, no .part, exit 0" '[ $st_ok -eq 0 ] && [ "$(ls "$TMP/b-ok"/*.dump | wc -l)" -eq 2 ] && ! ls "$TMP/b-ok"/*.part >/dev/null 2>&1'
if [ "$(uname -o 2>/dev/null)" != Msys ]; then check "dumps are mode 600" '[ "$(stat -c %a "$TMP/b-ok"/ipass-*.dump)" = 600 ]'; fi
mkdir -p "$TMP/b-fail"; : > "$TMP/b-fail/ipass-old.dump"; touch -d "10 days ago" "$TMP/b-fail/ipass-old.dump"
run_backup fail ipass "$TMP/b-fail"; st_fail=$?
check "a failed pg_dump is an error, leaves no .part and no fake dump" '[ $st_fail -ne 0 ] && ! ls "$TMP/b-fail"/*.part >/dev/null 2>&1 && ! ls "$TMP/b-fail"/ipass-2*.dump >/dev/null 2>&1'
check "after a failed dump nothing is pruned (the last good dump survives)" '[ -e "$TMP/b-fail/ipass-old.dump" ]'
check "the other database is still dumped when one fails" 'ls "$TMP/b-fail"/keycloak-*.dump >/dev/null 2>&1'
mkdir -p "$TMP/b-bad"; run_backup corrupt ipass "$TMP/b-bad"; st_bad=$?
check "a dump that pg_restore -l rejects is not kept and is an error" '[ $st_bad -ne 0 ] && ! ls "$TMP/b-bad"/ipass-*.dump >/dev/null 2>&1'
rout="$(DRY_RUN=1 sh "$HERE/backup/restore-test.sh")"
check "restore test restores into a scratch db as ipass_restore and drops it" 'grep -q "createdb .* -U ipass_restore .*ipass_restore_check" <<<"$rout" && grep -q "pg_restore .* -U ipass_restore .*--no-owner" <<<"$rout" && grep -q "dropdb .* -U ipass_restore .*ipass_restore_check" <<<"$rout"'
check "restore test counts rows as ipass_backup (the only login that sees FORCE-RLS rows)" 'grep -q "count.*ipass_backup" <<<"$rout"'
roles="$HERE/postgres-init/20-platform-roles.sh"
check "ipass_backup cannot create databases; ipass_restore can but bypasses no RLS" '! grep "ALTER ROLE ipass_backup" "$roles" | grep -qE "[^O]CREATEDB" && grep "ALTER ROLE ipass_backup" "$roles" | grep -q NOCREATEDB && grep "ALTER ROLE ipass_restore" "$roles" | grep -qE "[^O]CREATEDB" && grep "ALTER ROLE ipass_restore" "$roles" | grep -q NOBYPASSRLS'
check "roles script fails loudly on an over-privileged backup/restore split" 'grep -q "rolcreatedb" "$roles" && grep -q "ipass_restore" "$roles"'
check "pg-backup holds the one backup credential only" '! awk "/^  pg-backup:/,/^  pg-restore-test:/" "$HERE/compose.yaml" | grep -qE "KEYCLOAK_DB_PASSWORD|RESTORE_DB_PASSWORD|POSTGRES_PASSWORD"'
check "restore test is a one-shot profile service, not a resident container" 'awk "/^  pg-restore-test:/,/^networks:/" "$HERE/compose.yaml" | grep -q "profiles: \[\"restore-test\"\]"'
check "seed dry run needs no docker" 'DRY_RUN=1 sh "$HERE/seed/seed-showcase.sh" | grep -q showcase-admin-a'

# --- compose.yaml static controls (needs docker compose; dummy values only)
cat > "$TMP/c.env" <<EOF
IPASS_DOMAIN=example.test
IMAGE_TAG=abc1234
POSTGRES_PASSWORD=x
DB_MIGRATION_PASSWORD=x
DB_PASSWORD=x
BACKUP_DB_PASSWORD=x
RESTORE_DB_PASSWORD=x
KEYCLOAK_DB_PASSWORD=x
KC_BOOTSTRAP_ADMIN_PASSWORD=x
CONSOLE_OIDC_CLIENT_SECRET=x
PLATFORM_ADMIN_CLIENT_SECRET=x
OPERATOR_AGENT_CLIENT_SECRET=x
PLATFORM_CREDENTIAL_KEK_K1=x
EOF
if command -v docker >/dev/null 2>&1; then
  cfg="$(docker compose --env-file "$TMP/c.env" -f "$HERE/compose.yaml" -f "$HERE/demo/compose.demo.yaml" config 2>"$TMP/cfg.err")"
  check "docker compose config validates (with the demo override)" '[ -n "$cfg" ]'
  basecfg="$(docker compose --env-file "$TMP/c.env" -f "$HERE/compose.yaml" config 2>/dev/null)"
  check "production config (no override) holds no wiremock" '[ -n "$basecfg" ] && ! grep -qi wiremock <<<"$basecfg"'
  check "demo override adds wiremock and the showcase exception" 'grep -qi "image: wiremock" <<<"$cfg" && grep -q SHOWCASELOCALHOSTS <<<"$cfg"'
  check "no service mounts the Docker socket (base or demo)" '! grep -qi "docker.sock" <<<"$cfg"'
  check "no service is privileged or host-networked" '! grep -qE "privileged: true|network_mode: host|pid: host" <<<"$cfg"'
  check "docker compose config validates (no profile)" 'docker compose --env-file "$TMP/c.env" -f "$HERE/compose.yaml" config -q'
  check "no image tag is latest and none is untagged" '! grep -E "^\s+image:" <<<"$cfg" | grep -E ":latest|image: [^:@]+$"'
  check "no service publishes a port" '! grep -qE "^\s+ports:" <<<"$cfg"'
  check "required variable is enforced (no IMAGE_TAG fails)" '! docker compose --env-file <(grep -v IMAGE_TAG "$TMP/c.env") -f "$HERE/compose.yaml" config -q 2>/dev/null'
fi
check "postgres and kafka are never on the web network" 'awk "/^  postgres:/,/^  kafka:/" "$HERE/compose.yaml" | grep -q "networks: \[ipass-data\]" && awk "/^  kafka:/,/^  kafka-init:/" "$HERE/compose.yaml" | grep -q "networks: \[ipass-work\]"'
check "internal-only networks for data and work" 'grep -A1 "^  ipass-data:" "$HERE/compose.yaml" | grep -q "internal: true" && grep -A1 "^  ipass-work:" "$HERE/compose.yaml" | grep -q "internal: true"'
check "wiremock lives only in the demo override, internal network, no Traefik label, no port" '! grep -qi wiremock "$HERE/compose.yaml" && ! grep -q SHOWCASELOCAL "$HERE/compose.yaml" && grep -q "networks: \[ipass-work\]" "$HERE/demo/compose.demo.yaml" && ! grep -qE "traefik|ports:" "$HERE/demo/compose.demo.yaml"'
check "only the edge proxy joins the shared web network" '[ "$(awk "/^  [a-z-]+:\$/{svc=\$1} /networks: \[.*web/{print svc}" "$HERE/compose.yaml")" = "edge:" ]'
check "only the app has outbound reach (ipass-egress)" '[ "$(awk "/^  [a-z-]+:\$/{svc=\$1} /networks: \[.*ipass-egress/{print svc}" "$HERE/compose.yaml")" = "platform-app:" ] && ! grep -A2 "^  ipass-egress:" "$HERE/compose.yaml" | grep -q "internal: true"'
check "the front network (edge to app/ui/keycloak) is internal" 'grep -A1 "^  ipass-front:" "$HERE/compose.yaml" | grep -q "internal: true"'
check "edge refuses actuator, openapi, keycloak admin and the master realm" 'for p in actuator v3 admin realms/master; do grep -q "$p" "$HERE/edge/edge.conf" || exit 1; done; grep -c "return 404" "$HERE/edge/edge.conf" | grep -qE "^[3-9]"'
check "edge forwards only the paths the old Traefik rules routed to the app" 'for p in /api /oauth2 /login "= /logout"; do grep -q "location .*$p" "$HERE/edge/edge.conf" || exit 1; done'
if command -v docker >/dev/null 2>&1 && docker image inspect "ipass/ui:${NGINX_TEST_TAG:-none}" >/dev/null 2>&1; then
  check "edge.conf passes nginx -t (image ipass/ui:$NGINX_TEST_TAG)" 'MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -m "$HERE" 2>/dev/null || echo "$HERE")/edge/edge.conf:/etc/nginx/conf.d/default.conf:ro" "ipass/ui:$NGINX_TEST_TAG" nginx -t >/dev/null 2>&1'
fi
check "stateless services drop all capabilities and no-new-privileges" '[ "$(grep -c "<<: \*hardened" "$HERE/compose.yaml")" -ge 7 ] && grep -q "cap_drop: \[ALL\]" "$HERE/compose.yaml" && grep -q "no-new-privileges:true" "$HERE/compose.yaml"'
check "no public route for /v3, /actuator, Keycloak admin" '! grep -E "traefik.*rule=" "$HERE/compose.yaml" | grep -E "PathPrefix\(\`/(v3|actuator)\`" ; grep -E "ipass-auth.rule" "$HERE/compose.yaml" | grep -q "!PathPrefix(\`/admin\`)"'
check "every service has a restart policy or is one-shot" 'test "$(grep -c "restart:" "$HERE/compose.yaml")" -ge 7'
check "memory limit on every long-running service" 'test "$(grep -c "mem_limit:" "$HERE/compose.yaml")" -ge 8'

# --- Dockerfiles
check "app image runs non-root and has a health check" 'grep -q "^USER 10001" "$REPO/platform-app/Dockerfile" && grep -q "^HEALTHCHECK" "$REPO/platform-app/Dockerfile"'
check "no Dockerfile bakes a secret value" '! grep -iE "^(ENV|ARG).*(PASSWORD|SECRET|KEK)" "$REPO/platform-app/Dockerfile" "$REPO/ui/Dockerfile" "$HERE/keycloak/Dockerfile"'
check "scripts are LF" '! grep -lU $'"'"'\r'"'"' "$HERE"/*.sh "$HERE"/*/*.sh | grep -q .'
# --- git mode: the init scripts must be executable after a plain clone (Windows checkouts show 644 on disk, the index is what ships)
check "every .sh under deploy/hostinger is mode 100755 in the git index" '[ -z "$(git -C "$REPO" ls-files -s -- deploy/hostinger | grep "\.sh$" | grep -v "^100755")" ] && [ -n "$(git -C "$REPO" ls-files -- deploy/hostinger | grep "\.sh$")" ]'
echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"
exit $fail
