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
cc=""; roles=(); kvs=(); fields=""; args=("$@"); i=0
while [ $i -lt ${#args[@]} ]; do
  case "${args[$i]}" in --cclientid) cc="${args[$((i+1))]}";; --rolename) roles+=("${args[$((i+1))]}");; -s) kvs+=("${args[$((i+1))]}");; --fields) fields="${args[$((i+1))]}";; esac
  i=$((i+1))
done
case "$verb" in
 config)
   case "$path" in
     credentials) case "$all" in *--client*) sec="${all##*--secret }"; sec="${sec%% *}"; { [ -s "$S/secret" ] && [ "$sec" = "$(cat "$S/secret")" ]; } || { echo "invalid_client" >&2; exit 1; };; esac; exit 0;;
     truncate) echo "Unmatched argument at index 1: 'truncate'" >&2; exit 2;;   # the real 26.8.1 has no such sub-command (B1)
   esac;;
 get)
   case "$path" in
     clients) [ -e "$S/lookup-fails" ] && { echo "HTTP 500" >&2; exit 1; }; [ -e "$S/client" ] && echo c-1; exit 0;;
     clients/c-1) [ -e "$S/readfail" ] && { echo "HTTP 500" >&2; exit 1; }; v="$(grep "^$fields=" "$S/flags" | sed 's/^[^=]*=//')"; [ -n "$v" ] || exit 0; [ "$v" = '[]' ] && v='[ ]'; case "$v" in true|false|\[*) ;; *) v="\"$v\"";; esac; printf '{
  "%s" : %s
}
' "$fields" "$v"; exit 0;;
     realms/platform) echo '{ "realm" : "platform" }'; exit 0;;
   esac;;
 create)
   case "$path" in
     clients) echo c-1 > "$S/client"; echo "$all" > "$S/create-args"; : > "$S/flags"; for kv in "${kvs[@]}"; do case "$kv" in secret=*) ;; *) echo "$kv" >> "$S/flags";; esac; done; s="${all##*secret=}"; s="${s%% *}"; echo "$s" > "$S/secret"; echo c-1; exit 0;;
   esac;;
 update)
   case "$path" in
     clients/c-1) echo "$all" >> "$S/update-args"; for kv in "${kvs[@]}"; do case "$kv" in secret=*) echo "${kv#secret=}" > "$S/secret";; *) k="${kv%%=*}"; { grep -v "^$k=" "$S/flags"; echo "$kv"; } > "$S/f.n"; mv "$S/f.n" "$S/flags";; esac; done; exit 0;;
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
run() { S="$1"; mkdir -p "$S/.keycloak"; echo "token-and-secret" > "$S/.keycloak/kcadm.config"; OUT="$(env -u KC_CLIENT_ID -u ROTATE -u WITH_MASTER_USERS PATH="$PATH" HOME="$S" STUB_DIR="$S" KCADM="$T/kcadm" KC_ADMIN_USERNAME=adm KC_ADMIN_PASSWORD=pw KC_NEW_CLIENT_SECRET=newsecret-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA "${RUN_ENV[@]}" bash "$SCRIPT" 2>&1)"; RC=$?; }
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
[ ! -e "$S1/.keycloak/kcadm.config" ] && ! grep -q "config truncate" "$S1/calls" && ok "fresh: the kcadm token and stored secret are removed at exit (config file deleted; no 'config truncate', it does not exist in 26.8.1)" || bad "fresh: kcadm config file still there / config truncate used"
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

# 8. M3 (Karan): an existing client's security flags are read back and repaired on drift
grep -q -- "redirectUris=\[\]" <<<"$(cat "$S1/create-args")" && grep -q -- "webOrigins=\[\]" <<<"$(cat "$S1/create-args")" && ok "M3: create sets empty redirectUris and webOrigins explicitly" || bad "M3: create without explicit empty redirect lists"
grep -q "^get clients/c-1.*--fields publicClient" "$S2/calls" && grep -q "^get clients/c-1.*--fields redirectUris" "$S2/calls" && ok "M3: a re-run reads the flags and the redirect lists back" || bad "M3: no read-back in re-run: $(cat "$S2/calls")"
cp -r "$S1" "$T/flagdrift"; S8="$T/flagdrift"; : > "$S8/calls"; rm -f "$S8/update-args"
sed -i 's/^publicClient=.*/publicClient=true/; s/^directAccessGrantsEnabled=.*/directAccessGrantsEnabled=true/; s/^standardFlowEnabled=.*/standardFlowEnabled=true/' "$S8/flags"
sed -i 's#^redirectUris=.*#redirectUris=["https://evil.example/*"]#; s#^webOrigins=.*#webOrigins=["*"]#' "$S8/flags"
sec_before="$(cat "$S8/secret")"
RUN_ENV=("${NOSECRET[@]}"); run "$S8"; RUN_ENV=(X_UNUSED=1)
bad_left="$(grep -E '^(publicClient=true|directAccessGrantsEnabled=true|standardFlowEnabled=true|redirectUris=\["|webOrigins=\[")' "$S8/flags" | xargs)"
[ $RC -eq 0 ] && [ -z "$bad_left" ] && grep -q "^publicClient=false" "$S8/flags" && grep -q "^redirectUris=\[\]" "$S8/flags" && ok "M3: drifted flags and redirect URIs are repaired" || bad "M3: drift not repaired (rc=$RC left=[$bad_left]) $OUT"
for f in publicClient directAccessGrantsEnabled standardFlowEnabled redirectUris webOrigins; do grep -q "$f" <<<"$OUT" && ok "M3: drift of $f is reported" || bad "M3: $f drift not reported: $OUT"; done
[ "$(cat "$S8/secret")" = "$sec_before" ] && ! grep -qE "https://evil|newsecret|$sec_before" <<<"$OUT" && ok "M3: the secret is untouched and nothing secret is printed" || bad "M3: secret changed or printed"
S8b="$T/flagdrift2"; cp -r "$S1" "$S8b"; : > "$S8b/calls"; touch "$S8b/readfail"; RUN_ENV=("${NOSECRET[@]}"); run "$S8b"; RUN_ENV=(X_UNUSED=1)
[ $RC -ne 0 ] && ! grep -q "kcadm-client-setup done" <<<"$OUT" && ok "M3: a failing read-back aborts the run (no false 'present')" || bad "M3: read failure ignored (rc=$RC)"
S8c="$T/flagdrift3"; cp -r "$S1" "$S8c"; : > "$S8c/calls"; sed -i '/^serviceAccountsEnabled=/d' "$S8c/flags"
RUN_ENV=("${NOSECRET[@]}"); run "$S8c"; RUN_ENV=(X_UNUSED=1)
grep -q "^serviceAccountsEnabled=true" "$S8c/flags" && ok "M3: a flag that cannot be read back is treated as drift and set" || bad "M3: missing flag not repaired"

# 9. M5 / M2: reminders about the order (create -> retire -> drop master roles -> rotate -> TOTP)
S9="$(mk remind)"; RUN_ENV=(WITH_MASTER_USERS=1); run "$S9"; RUN_ENV=(X_UNUSED=1)
grep -qi "master-realm" <<<"$OUT" && grep -qi "retire-bootstrap-admin" <<<"$OUT" && grep -qi "TOTP" <<<"$OUT" && ok "M5: a run that grants master roles ends with a loud reminder (retire, drop roles, rotate, then TOTP)" || bad "M5: no reminder after master grant: $OUT"
cp -r "$S9" "$T/drop"; : > "$T/drop/calls"; RUN_ENV=("${NOSECRET[@]}"); run "$T/drop"; RUN_ENV=(X_UNUSED=1)
grep -qi "rotate" <<<"$OUT" && grep -qi "TOTP" <<<"$OUT" && ok "M5: the run that drops the master roles tells you to rotate the secret before TOTP" || bad "M5: no rotate reminder after dropping master roles: $OUT"

echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"; exit $fail
