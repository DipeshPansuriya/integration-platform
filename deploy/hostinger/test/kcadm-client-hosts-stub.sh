#!/bin/bash
# Behaviour test (row 543) of the HOST scripts that talk to Keycloak through `docker compose exec ... kcadm`: ops-setup.sh,
# retire-bootstrap-admin.sh, rotate-secret.sh, seed/seed-showcase.sh. A fake `compose` records every call, and the value of
# KC_CLIENT_SECRET it receives in its ENVIRONMENT. Proves: default login is the master service-account client (client
# credentials, secret travels as an env NAME, never in argv, never printed); the admin-password path exists only behind
# --password-login / KC_AUTH=password; a missing secret without a terminal stops the run; kcadm's token is truncated.
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0; ok() { echo "PASS $1"; }; bad() { echo "FAIL $1"; fail=1; }
SECRET="cs-0123456789-marker"
cat > "$T/fakecompose" <<'FAKE'
#!/bin/sh
echo "ARGS: $*" >> "$FAKE_LOG"
case "$*" in *"-e KC_CLIENT_SECRET"*) echo "ENVSECRET: ${KC_CLIENT_SECRET:-unset}" >> "$FAKE_LOG";; esac
case "$*" in
  *KC_BOOTSTRAP_ADMIN_PASSWORD*"-n"*|*'[ -n'*) [ "${FAKE_BOOTSTRAP:-0}" = 1 ]; exit $?;;
  *"get users"*) echo "${FAKE_USER_ID:-}"; exit 0;;
  *"get clients"*) echo cid-1; exit 0;;
  *"create users"*) echo new-id; exit 0;;
esac
cat >/dev/null 2>&1 || true
exit 0
FAKE
chmod +x "$T/fakecompose"
printf 'IPASS_DOMAIN=example.test\nPOSTGRES_USER=u\nPOSTGRES_DB=d\nDB_PASSWORD=x\nCONSOLE_OIDC_CLIENT_SECRET=old\nSHOWCASE_PASSWORD_ADMIN_A=a\nSHOWCASE_PASSWORD_CONFIGURATOR_A=b\nSHOWCASE_PASSWORD_APPROVER_A=c\nSHOWCASE_PASSWORD_ADMIN_B=d\nOPS_KAFBAT_CLIENT_SECRET=ops-existing-secret-0123456789abcdef\n' > "$T/.env"

# run NAME ENVASSIGNMENTS... -- script args...   -> sets RC, OUT; log in $T/log
run() { local name="$1"; shift; : > "$T/log"
  local envs=() ; while [ "$1" != "--" ]; do envs+=("$1"); shift; done; shift
  OUT="$(cd "$HERE" && env -i PATH="$PATH" HOME="$T" COMPOSE="$T/fakecompose" FAKE_LOG="$T/log" ENV_FILE="$T/.env" ROTATION_LOG="$T/rot.log" "${envs[@]}" "$@" </dev/null 2>&1)"; RC=$?; }
not_in_argv() { ! grep '^ARGS:' "$T/log" | grep -qF -- "$SECRET"; }
no_leak() { ! grep -qF -- "$SECRET" <<<"$OUT" && ! grep -qF -- "$SECRET" "$T/rot.log" 2>/dev/null; }
clientlogin() { grep '^ARGS:' "$T/log" | grep -q -- 'config credentials.*--realm master.*--client' && grep '^ARGS:' "$T/log" | grep -q -- '--secret'; }
nopwlogin() { ! grep '^ARGS:' "$T/log" | grep -q -- 'config credentials.*--user'; }
truncated() { grep '^ARGS:' "$T/log" | grep -q 'config truncate'; }
CS="KC_CLIENT_SECRET=$SECRET"

# --- retire-bootstrap-admin.sh
run retire "$CS" KC_ADMIN_USER=ignored -- sh keycloak/retire-bootstrap-admin.sh; 
[ $RC -eq 0 ] && clientlogin && nopwlogin && ok "retire: default login is the service-account client (no --user)" || bad "retire: default login (rc=$RC) $OUT"
grep -q "^ENVSECRET: $SECRET" "$T/log" && not_in_argv && no_leak && ok "retire: the secret travels in the environment only (not argv, not output)" || bad "retire: secret handling"
truncated && ok "retire: kcadm token truncated" || bad "retire: no config truncate"
run retire2 FAKE_USER_ID=u-1 -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -ne 0 ] && grep -qi "KC_CLIENT_SECRET\|terminal\|secret" <<<"$OUT" && ! grep -q 'config credentials' "$T/log" && ok "retire: no secret and no terminal -> stops before any login" || bad "retire: missing secret not refused (rc=$RC)"
run retire3 KC_ADMIN_USER=alice -- sh keycloak/retire-bootstrap-admin.sh --password-login
[ $RC -eq 0 ] && grep '^ARGS:' "$T/log" | grep -q -- 'config credentials.*--user alice' && ! grep -q -- '--client' "$T/log" && ok "retire: --password-login keeps the named-admin password path" || bad "retire: password fallback (rc=$RC) $OUT"
run retire4 KC_ADMIN_USER=alice KC_AUTH=password -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -eq 0 ] && grep '^ARGS:' "$T/log" | grep -q -- '--user alice' && ok "retire: KC_AUTH=password is the same explicit switch" || bad "retire: KC_AUTH=password"
run retire5 "$CS" DRY_RUN=1 -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -eq 0 ] && grep -q "ipass-kcadm" <<<"$OUT" && [ ! -s "$T/log" ] && ok "retire: dry run names the service-account client and calls nothing" || bad "retire: dry run ($OUT)"

# --- rotate-secret.sh (client secret)
run rot "$CS" -- sh rotate-secret.sh CONSOLE_OIDC_CLIENT_SECRET
[ $RC -eq 0 ] && clientlogin && nopwlogin && not_in_argv && no_leak && ok "rotate: client login, secret only in the environment" || bad "rotate: (rc=$RC) $OUT"
grep '^ARGS:' "$T/log" | grep -q 'update clients/cid-1' && truncated && ok "rotate: still updates the client and truncates the token" || bad "rotate: update/truncate"
run rot2 KC_ADMIN_USER=alice -- sh rotate-secret.sh --password-login CONSOLE_OIDC_CLIENT_SECRET
[ $RC -eq 0 ] && grep '^ARGS:' "$T/log" | grep -q -- '--user alice' && ok "rotate: --password-login fallback (flag before the name)" || bad "rotate: fallback (rc=$RC) $OUT"
run rot3 "$CS" -- sh rotate-secret.sh DB_PASSWORD
! grep -q 'config credentials' "$T/log" && ok "rotate: a DB secret needs no Keycloak login at all" || bad "rotate: DB secret logged in to Keycloak"

# --- seed/seed-showcase.sh
run seed "$CS" FAKE_BOOTSTRAP=0 -- sh seed/seed-showcase.sh
[ $RC -eq 0 ] && clientlogin && nopwlogin && not_in_argv && no_leak && ok "seed: bootstrap retired -> service-account client login" || bad "seed: (rc=$RC) $OUT"
run seed2 FAKE_BOOTSTRAP=1 -- sh seed/seed-showcase.sh
[ $RC -eq 0 ] && ! grep -q -- '--client' "$T/log" && ok "seed: first-start window keeps using the bootstrap admin (the client does not exist yet)" || bad "seed: bootstrap window (rc=$RC) $OUT"
run seed3 KC_ADMIN_USER=alice FAKE_BOOTSTRAP=0 -- sh seed/seed-showcase.sh --password-login
[ $RC -eq 0 ] && grep '^ARGS:' "$T/log" | grep -q -- '--user alice' && ok "seed: --password-login fallback" || bad "seed: fallback (rc=$RC) $OUT"
truncated && ok "seed: kcadm token truncated" || bad "seed: no config truncate"

# --- ops-setup.sh (feeds ops-setup-kcadm.sh into the container)
run ops "$CS" -- bash keycloak/ops-setup.sh
[ $RC -eq 0 ] && grep '^ARGS:' "$T/log" | grep -q -- '-e KC_CLIENT_SECRET' && ! grep -q 'KC_ADMIN_PASSWORD' "$T/log" && not_in_argv && no_leak && ok "ops-setup: client mode passes KC_CLIENT_SECRET by name and no admin password" || bad "ops-setup client mode (rc=$RC) $OUT"
run ops2 KC_ADMIN_USERNAME=alice KC_ADMIN_PASSWORD=pw -- bash keycloak/ops-setup.sh --password-login
[ $RC -eq 0 ] && grep '^ARGS:' "$T/log" | grep -q -- '-e KC_ADMIN_PASSWORD' && ok "ops-setup: --password-login passes the admin password by name" || bad "ops-setup password mode (rc=$RC) $OUT"
run ops3 DRY_RUN=1 -- bash keycloak/ops-setup.sh
grep -q "ipass-kcadm" <<<"$OUT" && ok "ops-setup: dry run names the client" || bad "ops-setup dry run: $OUT"

echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"; exit $fail
