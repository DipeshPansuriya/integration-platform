#!/bin/bash
# Behaviour test (row 543) of keycloak/kcadm-client-setup-kcadm.sh (runs inside the Keycloak container; fed by
# kcadm-client-setup.sh) against a STUB kcadm. Model: master-realm client ipass-kcadm (service account, client-credentials only),
# its service-account client-role mappings per target client (platform-realm, master-realm), its secret. Scenarios: fresh;
# re-run (no change, secret untouched); drift (extra role removed, missing role added); --rotate; master-users flag; failing lookup.
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$HERE/keycloak/kcadm-client-setup-kcadm.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0; ok() { echo "PASS $1"; }; bad() { echo "FAIL $1"; fail=1; }
cat > "$T/kcadm" <<'STUB'
#!/bin/bash
# Model files under $S: client (exists), secret, roles-<clientid> (service-account client-role mappings), create-args, update-args
S="${STUB_DIR:?}"; echo "$*" >> "$S/calls"
verb="$1"; path="${2:-}"
all="$*"
cc=""; roles=(); args=("$@"); i=0
while [ $i -lt ${#args[@]} ]; do
  case "${args[$i]}" in --cclientid) cc="${args[$((i+1))]}";; --rolename) roles+=("${args[$((i+1))]}");; esac
  i=$((i+1))
done
case "$verb" in
 config)
   case "$path" in
     credentials) case "$all" in *--client*) sec="${all##*--secret }"; sec="${sec%% *}"; { [ -s "$S/secret" ] && [ "$sec" = "$(cat "$S/secret")" ]; } || { echo "invalid_client" >&2; exit 1; };; esac; exit 0;;
     truncate) exit 0;;
   esac;;
 get)
   case "$path" in
     clients) [ -e "$S/lookup-fails" ] && { echo "HTTP 500" >&2; exit 1; }; [ -e "$S/client" ] && echo c-1; exit 0;;
     clients/c-1) echo '{ "id" : "c-1", "serviceAccountsEnabled" : true }'; exit 0;;
     realms/platform) echo '{ "realm" : "platform" }'; exit 0;;
   esac;;
 create)
   case "$path" in
     clients) echo c-1 > "$S/client"; echo "$all" > "$S/create-args"; s="${all##*secret=}"; s="${s%% *}"; echo "$s" > "$S/secret"; echo c-1; exit 0;;
   esac;;
 update)
   case "$path" in
     clients/c-1) echo "$all" >> "$S/update-args"; case "$all" in *secret=*) s="${all##*secret=}"; s="${s%% *}"; echo "$s" > "$S/secret";; esac; exit 0;;
   esac;;
 get-roles) f="$S/roles-$cc"; [ -s "$f" ] && cat "$f"; exit 0;;
 add-roles) for r in "${roles[@]}"; do echo "$r" >> "$S/roles-$cc"; done; exit 0;;
 remove-roles) for r in "${roles[@]}"; do grep -vx "$r" "$S/roles-$cc" > "$S/r.n"; mv "$S/r.n" "$S/roles-$cc"; done; exit 0;;
esac
echo "stub: unhandled $all" >&2; exit 9
STUB
chmod +x "$T/kcadm"
mk() { rm -rf "$T/$1"; mkdir -p "$T/$1"; : > "$T/$1/calls"; echo "$T/$1"; }
RUN_ENV=(X_UNUSED=1)
run() { S="$1"; OUT="$(env -u KC_CLIENT_ID -u ROTATE -u WITH_MASTER_USERS PATH="$PATH" STUB_DIR="$S" KCADM="$T/kcadm" KC_ADMIN_USERNAME=adm KC_ADMIN_PASSWORD=pw KC_NEW_CLIENT_SECRET=newsecret-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA "${RUN_ENV[@]}" bash "$SCRIPT" 2>&1)"; RC=$?; }
PLATFORM="manage-clients manage-realm manage-users view-clients view-realm view-users"
NOSECRET=(KC_NEW_CLIENT_SECRET=)

# 1. fresh
S1="$(mk fresh)"; run "$S1"
[ $RC -eq 0 ] && ok "fresh: the script succeeds" || bad "fresh rc=$RC: $OUT"
c="$(cat "$S1/create-args" 2>/dev/null)"
for f in serviceAccountsEnabled=true publicClient=false standardFlowEnabled=false directAccessGrantsEnabled=false implicitFlowEnabled=false clientId=ipass-kcadm; do
  grep -q -- "$f" <<<"$c" && ok "fresh: client created with $f" || bad "fresh: $f missing in [$c]"
done
got="$(sort "$S1/roles-platform-realm" 2>/dev/null | xargs)"; [ "$got" = "$PLATFORM" ] && ok "fresh: service account has exactly the six platform-realm roles" || bad "fresh: platform roles [$got]"
[ ! -s "$S1/roles-master-realm" ] && ok "fresh: no master-realm role without the flag (least privilege)" || bad "fresh: master roles granted by default"
grep -q "^config credentials.*--client ipass-kcadm --secret newsecret" "$S1/calls" && ok "fresh: the new secret is verified by a real client-credentials login" || bad "fresh: no verification login"
grep -q "^get realms/platform" "$S1/calls" && ok "fresh: and a realm call with it (platform)" || bad "fresh: no verification call"
tail -1 "$S1/calls" | grep -q "config truncate" && ok "fresh: the kcadm token is truncated at exit" || bad "fresh: no truncate"
! grep -q "newsecret" <<<"$OUT" && ok "fresh: the secret is not printed" || bad "fresh: secret in output"
! grep -qE "create users|set-password|realm-admin" "$S1/calls" && ok "fresh: no user is created and the all-powerful realm-admin role is never used" || bad "fresh: user or realm-admin"

# 2. re-run: nothing changes, secret untouched, no secret needed
cp -r "$S1" "$T/rerun"; S2="$T/rerun"; : > "$S2/calls"; rm -f "$S2/update-args"; RUN_ENV=("${NOSECRET[@]}"); run "$S2"; RUN_ENV=(X_UNUSED=1)
[ $RC -eq 0 ] && ! grep -q "^create clients\|^add-roles\|^remove-roles" "$S2/calls" && [ ! -s "$S2/update-args" ] && ok "re-run: nothing created, granted, removed or updated; no secret needed" || bad "re-run changed something (rc=$RC): $(cat "$S2/calls")"
grep -q "unchanged" <<<"$OUT" && ok "re-run: reports unchanged" || bad "re-run output: $OUT"

# 3. drift: an extra role is removed, a missing one is added
cp -r "$S1" "$T/drift"; S3="$T/drift"; : > "$S3/calls"
grep -vx view-users "$S3/roles-platform-realm" > "$S3/r.n"; mv "$S3/r.n" "$S3/roles-platform-realm"; echo realm-admin >> "$S3/roles-platform-realm"; echo manage-users >> "$S3/roles-master-realm"
RUN_ENV=("${NOSECRET[@]}"); run "$S3"; RUN_ENV=(X_UNUSED=1)
got="$(sort "$S3/roles-platform-realm" | xargs)"; [ "$got" = "$PLATFORM" ] && [ ! -s "$S3/roles-master-realm" ] && ok "drift: realm-admin and a stray master role removed, view-users added back" || bad "drift: [$got] master=[$(cat "$S3/roles-master-realm")]"

# 4. --rotate: new secret set and verified; without a new secret it stops
cp -r "$S1" "$T/rot"; S4="$T/rot"; : > "$S4/calls"; RUN_ENV=(ROTATE=1 KC_NEW_CLIENT_SECRET=rotated-BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB); run "$S4"; RUN_ENV=(X_UNUSED=1)
[ $RC -eq 0 ] && [ "$(cat "$S4/secret")" = "rotated-BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" ] && grep -q "^config credentials.*--secret rotated-" "$S4/calls" && ok "rotate: the secret is replaced and the new one verified by login" || bad "rotate (rc=$RC): $OUT"
cp -r "$S1" "$T/rot2"; S4b="$T/rot2"; : > "$S4b/calls"; rm -f "$S4b/update-args"; RUN_ENV=(ROTATE=1 "${NOSECRET[@]}"); run "$S4b"; RUN_ENV=(X_UNUSED=1)
[ $RC -ne 0 ] && [ ! -s "$S4b/update-args" ] && ok "rotate: without a new secret it stops and changes nothing" || bad "rotate without secret (rc=$RC)"
S4c="$(mk weak)"; RUN_ENV=(KC_NEW_CLIENT_SECRET=short); run "$S4c"; RUN_ENV=(X_UNUSED=1)
[ $RC -ne 0 ] && ! grep -q "^create clients" "$S4c/calls" && ok "a secret shorter than 32 characters is refused before anything is created" || bad "weak secret accepted (rc=$RC)"

# 5. master-users flag
S5="$(mk master)"; RUN_ENV=(WITH_MASTER_USERS=1); run "$S5"; RUN_ENV=(X_UNUSED=1)
got="$(sort "$S5/roles-master-realm" 2>/dev/null | xargs)"; [ "$got" = "manage-users view-users" ] && ok "WITH_MASTER_USERS=1 adds master-realm manage-users and view-users (retire-bootstrap-admin)" || bad "master roles [$got]"

# 6. failing lookup stops the run: no duplicate create
S6="$(mk lookupfail)"; touch "$S6/lookup-fails"; run "$S6"
[ $RC -ne 0 ] && ! grep -q "^create clients" "$S6/calls" && ok "a failing client lookup aborts (no create)" || bad "lookup failure treated as absent (rc=$RC)"

# 7. custom client id
S7="$(mk cid)"; RUN_ENV=(KC_CLIENT_ID=other-kcadm); run "$S7"; RUN_ENV=(X_UNUSED=1)
grep -q -- "--uusername service-account-other-kcadm" "$S7/calls" && ok "KC_CLIENT_ID overrides the client id (service-account user follows)" || bad "client id override"

echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"; exit $fail
