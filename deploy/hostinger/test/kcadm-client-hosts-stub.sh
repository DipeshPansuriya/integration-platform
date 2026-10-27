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
[ "${FAKE_FAIL:-0}" = 1 ] && exit 7
case "$*" in *"-e KC_CLIENT_SECRET"*) echo "ENVSECRET: ${KC_CLIENT_SECRET:-unset}" >> "$FAKE_LOG";; esac
case "$*" in *"-e KC_NEW_CLIENT_SECRET"*) echo "ENVNEW: new=${KC_NEW_CLIENT_SECRET:-unset} pw=${KC_ADMIN_PASSWORD:-unset} rotate=${ROTATE:-} master=${WITH_MASTER_USERS:-} client=${KC_CLIENT_ID:-}" >> "$FAKE_LOG"; echo "STDIN-BYTES: $(wc -c)" >> "$FAKE_LOG"; exit 0;; esac
case "$*" in
  *KC_BOOTSTRAP_ADMIN_PASSWORD*"-n"*|*'[ -n'*) [ "${FAKE_BOOTSTRAP:-0}" = 1 ]; exit $?;;
  *"get users"*) [ "${FAKE_FAIL_USERS:-0}" = 1 ] && { echo "HTTP 403 Forbidden" >&2; exit 1; }; case "$*" in *"username=bootstrap-admin"*) echo "${FAKE_USER_ID:-}"; exit 0;; *) echo "${FAKE_NAMED:-}"; exit 0;; esac;;
  *"get users"*"username=bootstrap-admin"*) echo "${FAKE_USER_ID:-}"; exit 0;;
  *"get users"*) echo "${FAKE_NAMED:-}"; exit 0;;
  *"get-roles"*) echo "${FAKE_NAMED_ROLES:-admin}"; exit 0;;
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
# B1 (proof, Keycloak 26.8.1): `kcadm.sh config truncate` does not exist (config knows only credentials and truststore), so the token
# and the stored client secret are removed by deleting the config file in the container instead; "config truncate" must not be used.
truncated() { grep '^ARGS:' "$T/log" | grep -q 'rm -f .*kcadm.config' && ! grep '^ARGS:' "$T/log" | grep -q 'config truncate'; }
CS="KC_CLIENT_SECRET=$SECRET"

# --- retire-bootstrap-admin.sh
run retire "$CS" KC_ADMIN_USER=ignored "FAKE_NAMED=nid-1,true" -- sh keycloak/retire-bootstrap-admin.sh; 
[ $RC -eq 0 ] && clientlogin && nopwlogin && ok "retire: default login is the service-account client (no --user)" || bad "retire: default login (rc=$RC) $OUT"
grep -q "^ENVSECRET: $SECRET" "$T/log" && not_in_argv && no_leak && ok "retire: the secret travels in the environment only (not argv, not output)" || bad "retire: secret handling"
truncated && ok "retire: kcadm token truncated" || bad "retire: no config truncate"
run retire2 KC_ADMIN_USER=alice FAKE_USER_ID=u-1 -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -ne 0 ] && grep -qi "KC_CLIENT_SECRET\|terminal\|secret" <<<"$OUT" && ! grep -q 'config credentials' "$T/log" && ok "retire: no secret and no terminal -> stops before any login" || bad "retire: missing secret not refused (rc=$RC)"
run retire3 KC_ADMIN_USER=alice "FAKE_NAMED=nid-1,true" -- sh keycloak/retire-bootstrap-admin.sh --password-login
[ $RC -eq 0 ] && grep '^ARGS:' "$T/log" | grep -q -- 'config credentials.*--user alice' && ! grep -q -- '--client' "$T/log" && ok "retire: --password-login keeps the named-admin password path" || bad "retire: password fallback (rc=$RC) $OUT"
run retire4 KC_ADMIN_USER=alice "FAKE_NAMED=nid-1,true" KC_AUTH=password -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -eq 0 ] && grep '^ARGS:' "$T/log" | grep -q -- '--user alice' && ok "retire: KC_AUTH=password is the same explicit switch" || bad "retire: KC_AUTH=password"
run retire5 "$CS" KC_ADMIN_USER=alice DRY_RUN=1 -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -eq 0 ] && grep -q "ipass-kcadm" <<<"$OUT" && [ ! -s "$T/log" ] && ok "retire: dry run names the service-account client and calls nothing" || bad "retire: dry run ($OUT)"

# --- M1 (Karan): client mode must prove a named admin exists before bootstrap-admin is deleted (lock-out guard)
run m1a "$CS" FAKE_USER_ID=u-1 -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -ne 0 ] && grep -q "KC_ADMIN_USER" <<<"$OUT" && [ ! -s "$T/log" ] && ok "retire M1: client mode without KC_ADMIN_USER stops before any login or delete" || bad "retire M1a (rc=$RC) $OUT"
run m1b "$CS" KC_ADMIN_USER=bootstrap-admin FAKE_USER_ID=u-1 -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -ne 0 ] && [ ! -s "$T/log" ] && ok "retire M1: the named admin may not be bootstrap-admin itself (client mode too)" || bad "retire M1b (rc=$RC) $OUT"
run m1c "$CS" KC_ADMIN_USER=alice FAKE_USER_ID=u-1 FAKE_NAMED= -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -ne 0 ] && ! grep -q 'delete users' "$T/log" && ! grep -q 'force-recreate' "$T/log" && truncated && ok "retire M1: named admin absent -> refused, nothing deleted, token truncated" || bad "retire M1c (rc=$RC) $OUT"
run m1d "$CS" KC_ADMIN_USER=alice FAKE_USER_ID=u-1 "FAKE_NAMED=nid-1,false" -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -ne 0 ] && ! grep -q 'delete users' "$T/log" && ok "retire M1: named admin disabled -> refused, nothing deleted" || bad "retire M1d (rc=$RC) $OUT"
run m1e "$CS" KC_ADMIN_USER=alice FAKE_USER_ID=u-1 "FAKE_NAMED=nid-1,true" FAKE_NAMED_ROLES=default-roles-master -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -ne 0 ] && ! grep -q 'delete users' "$T/log" && ok "retire M1: named admin without the admin role -> refused, nothing deleted" || bad "retire M1e (rc=$RC) $OUT"
run m1f "$CS" KC_ADMIN_USER=alice FAKE_USER_ID=u-1 "FAKE_NAMED=nid-1,true" -- sh keycloak/retire-bootstrap-admin.sh
lookup=$(grep -n 'username=alice' "$T/log" | head -1 | cut -d: -f1); del=$(grep -n 'delete users/u-1' "$T/log" | head -1 | cut -d: -f1)
[ $RC -eq 0 ] && [ -n "$lookup" ] && [ -n "$del" ] && [ "$lookup" -lt "$del" ] && grep -q 'exact=true' "$T/log" && ok "retire M1: named admin checked (exact match) BEFORE bootstrap-admin is deleted" || bad "retire M1f (rc=$RC lookup=$lookup del=$del) $OUT"
grep -qi "without --with-master-users" <<<"$OUT" && grep -qi "rotate" <<<"$OUT" && grep -qi "TOTP" <<<"$OUT" && ok "retire M5: after the delete it says drop the master roles, rotate the secret, then TOTP" || bad "retire M5 reminder missing: $OUT"
run m1g KC_ADMIN_USER=alice FAKE_USER_ID=u-1 "FAKE_NAMED=nid-1,true" -- sh keycloak/retire-bootstrap-admin.sh --password-login
[ $RC -eq 0 ] && grep -q 'username=alice' "$T/log" && ok "retire M1: the same guard runs in password mode" || bad "retire M1g (rc=$RC) $OUT"

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

# --- L3: the password fallback is loud; the client path is quiet
run l3a KC_ADMIN_USER=alice "FAKE_NAMED=nid-1,true" -- sh keycloak/retire-bootstrap-admin.sh --password-login
grep -q "WARNING" <<<"$OUT" && grep -qi "TOTP" <<<"$OUT" && ok "L3: --password-login prints a warning (works only while the admin has no TOTP)" || bad "L3: no warning: $OUT"
run l3b "$CS" KC_ADMIN_USER=alice "FAKE_NAMED=nid-1,true" -- sh keycloak/retire-bootstrap-admin.sh
! grep -q "WARNING" <<<"$OUT" && ok "L3: the client path prints no password warning" || bad "L3: warning in client mode"
run l3c KC_AUTH=bogus-value-xyz -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -ne 0 ] && ! grep -q "bogus-value-xyz" <<<"$OUT" && ok "L6: a bad KC_AUTH is refused without echoing the value" || bad "L6: KC_AUTH echoed or accepted (rc=$RC) $OUT"

# --- L6: KC_CLIENT_ID is validated before it reaches a shell string
run l6a "$CS" "KC_CLIENT_ID=x'; echo pwned; '" KC_ADMIN_USER=alice "FAKE_NAMED=nid-1,true" -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -ne 0 ] && [ ! -s "$T/log" ] && ok "L6: a KC_CLIENT_ID with shell characters is refused before any call" || bad "L6: client id accepted (rc=$RC) $OUT"
run l6b "$CS" KC_CLIENT_ID=other.kcadm_1-x KC_ADMIN_USER=alice "FAKE_NAMED=nid-1,true" -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -eq 0 ] && grep -q -- "--client 'other.kcadm_1-x'" "$T/log" && ok "L6: a normal custom client id is used" || bad "L6: custom client id (rc=$RC) $OUT"

# --- L7: ops-setup reads the client secret BEFORE it writes the generated secret into .env
printf 'IPASS_DOMAIN=example.test\n' > "$T/.env.l7"
run l7 ENV_FILE="$T/.env.l7" -- bash keycloak/ops-setup.sh
[ $RC -ne 0 ] && ! grep -q "OPS_KAFBAT_CLIENT_SECRET" "$T/.env.l7" && [ ! -s "$T/log" ] && ok "L7: no client secret -> ops-setup stops BEFORE .env is changed" || bad "L7: .env changed or call made (rc=$RC) $(cat "$T/.env.l7")"
printf 'IPASS_DOMAIN=example.test\n' > "$T/.env.l7"
run l7b "$CS" ENV_FILE="$T/.env.l7" -- bash keycloak/ops-setup.sh
grep -q "^OPS_KAFBAT_CLIENT_SECRET=." "$T/.env.l7" && ok "L7: with the secret, the generated value is still written" || bad "L7: not generated (rc=$RC) $OUT"

# --- L4: the terminal is restored if the secret prompt is interrupted
grep -q "trap .*stty" "$HERE/keycloak/kc-login.sh" && ok "L4: kc-login.sh restores the terminal settings from a trap" || bad "L4: no stty trap in kc-login.sh"

# --- L8: the host wrapper kcadm-client-setup.sh
AEV=(KC_ADMIN_USERNAME=adm KC_ADMIN_PASSWORD=ADMPW-marker-77 KC_NEW_CLIENT_SECRET=NEWSEC-marker-0123456789abcdefABCDEF)
leak_w() { ! grep '^ARGS:' "$T/log" | grep -qE "ADMPW-marker|NEWSEC-marker" && ! grep -qE "ADMPW-marker|NEWSEC-marker" <<<"$OUT"; }
run w1 -- bash keycloak/kcadm-client-setup.sh
[ $RC -ne 0 ] && grep -qi "terminal" <<<"$OUT" && [ ! -s "$T/log" ] && ok "wrapper: no input and no terminal -> stops before any call" || bad "wrapper w1 (rc=$RC) $OUT"
run w2 "${AEV[@]}" -- bash keycloak/kcadm-client-setup.sh
[ $RC -eq 0 ] && grep '^ARGS:' "$T/log" | grep -q -- "-e KC_ADMIN_PASSWORD -e KC_CLIENT_ID -e KC_NEW_CLIENT_SECRET" && leak_w && grep -q "^ENVNEW: new=NEWSEC-marker-0123456789abcdefABCDEF pw=ADMPW-marker-77 rotate=0 master=0 client=ipass-kcadm" "$T/log" && ok "wrapper: secrets travel by environment name only; default flags 0; default client id" || bad "wrapper w2 (rc=$RC) $OUT $(cat "$T/log")"
n=$(sed -n 's/^STDIN-BYTES: *//p' "$T/log"); [ "${n:-0}" -gt 1000 ] && ok "wrapper: the container script is fed on stdin (bash -s)" || bad "wrapper: stdin not fed ($n)"
run w3 "${AEV[@]}" -- bash keycloak/kcadm-client-setup.sh --rotate --with-master-users
[ $RC -eq 0 ] && grep -q "rotate=1 master=1" "$T/log" && leak_w && ok "wrapper: --rotate and --with-master-users reach the container as ROTATE=1 / WITH_MASTER_USERS=1" || bad "wrapper w3 (rc=$RC) $OUT"
run w4 "${AEV[@]}" -- bash keycloak/kcadm-client-setup.sh --bogus
[ $RC -eq 2 ] && [ ! -s "$T/log" ] && ok "wrapper: an unknown flag is refused (rc 2), nothing called" || bad "wrapper w4 (rc=$RC) $OUT"
run w5 "${AEV[@]}" "KC_CLIENT_ID=a b;c" -- bash keycloak/kcadm-client-setup.sh
[ $RC -ne 0 ] && [ ! -s "$T/log" ] && ok "wrapper: an unsafe KC_CLIENT_ID is refused before any call" || bad "wrapper w5 (rc=$RC) $OUT"
run w6 "${AEV[@]}" FAKE_FAIL=1 -- bash keycloak/kcadm-client-setup.sh
[ $RC -ne 0 ] && ! grep -q "store the client secret" <<<"$OUT" && leak_w && ok "wrapper: a failing container run fails the wrapper (no success line)" || bad "wrapper w6 (rc=$RC) $OUT"
run w7 DRY_RUN=1 -- bash keycloak/kcadm-client-setup.sh --with-master-users
[ $RC -eq 0 ] && [ ! -s "$T/log" ] && grep -q "ipass-kcadm" <<<"$OUT" && grep -q "master-realm: manage-users view-users" <<<"$OUT" && ok "wrapper: dry run calls nothing and names the roles" || bad "wrapper w7 (rc=$RC) $OUT"

# --- proof findings (Keycloak 26.8.1): N1 Keycloak lower-cases user names, so the bootstrap-admin comparison must too;
# a failing user lookup (403 without the master roles) must be reported as such, not as "named admin not found"
run n1 "$CS" KC_ADMIN_USER=Bootstrap-Admin FAKE_USER_ID=u-1 -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -ne 0 ] && [ ! -s "$T/log" ] && ok "retire N1: Bootstrap-Admin (any case) is refused as the named admin, before any login" || bad "retire N1 (rc=$RC) $OUT"
run p403 "$CS" KC_ADMIN_USER=alice FAKE_USER_ID=u-1 FAKE_FAIL_USERS=1 -- sh keycloak/retire-bootstrap-admin.sh
[ $RC -ne 0 ] && ! grep -q 'delete users' "$T/log" && ! grep -q 'force-recreate' "$T/log" && grep -qi "master-realm" <<<"$OUT" && ! grep -qi "not found in realm" <<<"$OUT" && ok "retire 403: a failed lookup says the master roles are missing (not 'admin not found'), nothing deleted" || bad "retire 403 message (rc=$RC) $OUT"

echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"; exit $fail
