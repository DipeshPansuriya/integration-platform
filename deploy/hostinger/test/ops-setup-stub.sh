#!/bin/bash
# Behaviour test of keycloak/ops-setup-kcadm.sh against a STUB kcadm (no Keycloak, no docker). The stub keeps a small
# in-memory model (files under $S) of roles, clients, flows and executions and logs every call to $S/calls.
# Scenarios: fresh realm; a valid flow already there (re-run changes nothing); a half-built flow (no OTP) that must be rebuilt
# and bound only after it verified; a failing `get clients` that must stop the script instead of creating a duplicate.
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$HERE/keycloak/ops-setup-kcadm.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0; ok() { echo "PASS $1"; }; bad() { echo "FAIL $1"; fail=1; }

cat > "$T/kcadm" <<'STUB'
#!/bin/bash
# usage as kcadm: verb path [-r realm] [options]. Model: $S/{roles,clients,flows,execs,mappings,mappers,bind,config}
S="${STUB_DIR:?}"; echo "$*" >> "$S/calls"
verb="$1"; path="${2:-}"; shift 2 2>/dev/null || true
args="$*"
disp() { case "$1" in auth-username-password-form) echo "Username Password Form";; conditional-user-role) echo "Condition - user role";; deny-access-authenticator) echo "Deny access";; auth-otp-form) echo "OTP Form";; *) echo "$1";; esac; }
level_of() { [ "$1" = ops-browser ] && echo -1 || awk -F'|' -v a="$1" '$3==a{print $6; exit}' "$S/execs"; }   # row: parent|id|name|provider|req|level|cfg
n=$(( $(cat "$S/counter" 2>/dev/null || echo 0) + 1 )); echo $n > "$S/counter"
case "$verb $path" in
 "config credentials"|"config truncate") exit 0;;
 "get roles") [ -s "$S/roles" ] && cat "$S/roles"; exit 0;;
 "get roles/ops-operator") echo "r-1"; exit 0;;
 "create roles") echo ops-operator > "$S/roles"; exit 0;;
 "get clients") [ -e "$S/get-clients-fails" ] && { echo "HTTP 500" >&2; exit 1; }; [ -s "$S/clients" ] && echo c-1; exit 0;;
 "create clients") case "$args" in *enabled=false*) echo false > "$S/enabled";; *) echo true > "$S/enabled";; esac; echo c-1 > "$S/clients"; printf 'ds-1,platform-api-audience
ds-2,profile
ds-3,roles
' > "$S/dscopes"; echo c-1; exit 0;;
 "get clients/c-1/default-client-scopes") [ -e "$S/dscopes-get-fails" ] && exit 1; cat "$S/dscopes" 2>/dev/null; exit 0;;
 "delete clients/c-1/default-client-scopes/"*) id="${path##*/}"; grep -v "^$id," "$S/dscopes" > "$S/ds.n"; mv "$S/ds.n" "$S/dscopes"; exit 0;;
 "update clients/c-1")
   case "$args" in *enabled=true*) echo true > "$S/enabled";; *enabled=false*) echo false > "$S/enabled";; esac
   case "$args" in *authenticationFlowBindingOverrides.browser=*) b="${args##*authenticationFlowBindingOverrides.browser=}"; b="${b%% *}"; echo "$b" > "$S/bind";; esac; exit 0;;
 "get clients/c-1") echo "{ \"enabled\" : $(cat "$S/enabled" 2>/dev/null || echo true), \"authenticationFlowBindingOverrides\" : { \"browser\" : \"$(cat "$S/bind" 2>/dev/null)\" } }"; exit 0;;
 "get clients/c-1/scope-mappings/realm") cat "$S/mappings" 2>/dev/null; exit 0;;
 "create clients/c-1/scope-mappings/realm") echo ops-operator > "$S/mappings"; exit 0;;
 "get clients/c-1/protocol-mappers/models") cat "$S/mappers" 2>/dev/null; exit 0;;
 "create clients/c-1/protocol-mappers/models") echo roles > "$S/mappers"; exit 0;;
 "get authentication/flows") [ -s "$S/flowid" ] && echo "$(cat "$S/flowid"),ops-browser"; exit 0;;
 "create authentication/flows") echo "f-$n" > "$S/flowid"; : > "$S/execs"; exit 0;;
 "delete authentication/flows/"*) : > "$S/flowid"; : > "$S/execs"; echo "deleted" >> "$S/calls"; exit 0;;
 "create authentication/flows/"*"/executions/flow")
   parent="${path#authentication/flows/}"; parent="${parent%%/*}"; alias="${args##*alias=}"; alias="${alias%% *}"
   echo "$parent|e$n|$alias||DISABLED|$(( $(level_of "$parent") + 1 ))|" >> "$S/execs"; exit 0;;
 "create authentication/flows/"*"/executions/execution")
   parent="${path#authentication/flows/}"; parent="${parent%%/*}"; prov="${args##*provider=}"; prov="${prov%% *}"
   echo "$parent|e$n|$(disp "$prov")|$prov|DISABLED|$(( $(level_of "$parent") + 1 ))|" >> "$S/execs"; exit 0;;
 "create authentication/executions/"*"/config")
   id="${path#authentication/executions/}"; id="${id%%/*}"; echo "$args" > "$S/cfg-$id"; sed -i "s/^\(.*|$id|.*|.*|.*|.*|\).*/\1cfg-$id/" "$S/execs"; exit 0;;
 "update authentication/flows/"*"/executions")
   id="$(echo "$args" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"; rq="$(echo "$args" | sed -n 's/.*"requirement":"\([^"]*\)".*/\1/p')"
   awk -F'|' -v OFS='|' -v id="$id" -v rq="$rq" '$2==id{$5=rq}1' "$S/execs" > "$S/execs.n" && mv "$S/execs.n" "$S/execs"; exit 0;;
 "get authentication/flows/"*"/executions") [ -e "$S/exec-get-fails" ] && exit 1
   fl="${path#authentication/flows/}"; fl="${fl%%/*}"
   # depth-first listing of the subtree under $fl
   walk() { awk -F'|' -v p="$1" '$1==p{print $3}' "$S/execs" | while read -r nm; do :; done; }
   emit() { # parent
     while IFS='|' read -r par id nm prov rq lv cfg; do
       [ "$par" = "$1" ] || continue
       case "$args" in
         *id,displayName,providerId*) echo "$id,$nm,$prov";;
         *) echo "$nm,$prov,$rq,$lv,$cfg";;
       esac
       [ -z "$prov" ] && emit "$nm"
     done < "$S/execs"; }
   emit "$fl"; exit 0;;
 "get authentication/config/"*) id="${path#authentication/config/}"; f="$S/$id"; [ -e "$f" ] || exit 1
   echo "{ \"alias\" : \"x\", \"config\" : {"; for kv in $(grep -o 'config\.[A-Za-z]*=[a-z-]*' "$f"); do k="${kv#config.}"; echo "\"${k%%=*}\" : \"${kv#*=}\","; done; echo "} }"; exit 0;;
esac
echo "stub: unhandled $verb $path $args" >&2; exit 9
STUB
# the stub itself may use awk (it runs on this PC); the SCRIPT under test must not: the keycloak container has no awk.
sed -i "s#awk -F#$(command -v awk) -F#g" "$T/kcadm"; chmod +x "$T/kcadm"
mkdir -p "$T/noawk"; printf '#!/bin/sh
echo "awk is not in the keycloak container" >&2; exit 127
' > "$T/noawk/awk"; chmod +x "$T/noawk/awk"

# Row 543: the default login is the master service-account client (KC_CLIENT_ID + KC_CLIENT_SECRET, no admin user/password);
# the admin-password login is the explicit fallback (KC_AUTH=password). RUN_ENV adds/overrides variables for one scenario.
RUN_ENV=()
run() { # scenario-dir -> runs the real script against the stub; sets RC and OUT
  S="$1"; OUT="$(env -u KC_ADMIN_USERNAME -u KC_ADMIN_PASSWORD -u KC_AUTH -u KC_CLIENT_ID PATH="$T/noawk:$PATH" STUB_DIR="$S" KCADM="$T/kcadm" IPASS_DOMAIN=example.test KC_CLIENT_SECRET=cs OPS_KAFBAT_CLIENT_SECRET=s "${RUN_ENV[@]}" bash "$SCRIPT" 2>&1)"; RC=$?; }
mk() { rm -rf "$T/$1"; mkdir -p "$T/$1"; : > "$T/$1/calls"; echo "$T/$1"; }

# 1. fresh realm
S1="$(mk fresh)"; run "$S1"
[ $RC -eq 0 ] && ok "fresh realm: the script succeeds" || bad "fresh realm exit $RC: $OUT"
c="$S1/calls"
grep -q "negate=true" "$c" && grep -q "condUserRole=ops-operator" "$c" && ok "fresh: the role gate is configured with negate=true for ops-operator" || bad "fresh: negate=true missing"
a=$(grep -n "provider=auth-username-password-form" "$c" | head -1 | cut -d: -f1); b=$(grep -n "alias=ops-role-gate" "$c" | head -1 | cut -d: -f1); d=$(grep -n "provider=auth-otp-form" "$c" | head -1 | cut -d: -f1)
[ -n "$a" ] && [ -n "$b" ] && [ -n "$d" ] && [ "$a" -lt "$b" ] && [ "$b" -lt "$d" ] && ok "fresh: order is password, then the role gate, then OTP" || bad "fresh: order a=$a b=$b d=$d"
[ "$(grep -c 'authenticationFlowBindingOverrides.browser=f-' "$c")" -eq 1 ] && ok "fresh: the flow is bound to the client exactly once" || bad "fresh: bind count"
lastbuild=$(grep -n "create authentication/flows/ops-browser-forms/executions/execution -r platform -s provider=auth-otp-form\|update authentication/flows/ops-browser-forms/executions" "$c" | tail -1 | cut -d: -f1); bindl=$(grep -n 'authenticationFlowBindingOverrides.browser=f-' "$c" | head -1 | cut -d: -f1)
[ "$lastbuild" -lt "$bindl" ] && ok "fresh: binding comes after the last build step" || bad "fresh: bound before the build finished"
grep -q "directAccessGrantsEnabled=false" "$c" && grep -q "fullScopeAllowed=false" "$c" && ok "fresh: client is created with full scope and the password grant off" || bad "fresh: client flags"
! grep -q "create users\|set-password" "$c" && ok "no user is created or given a password" || bad "user created"
grep -q "$OUT" /dev/null 2>/dev/null; ! grep -qE "(^| )(p|s)( |$)" <<<"$OUT" && ok "the output carries no secret value" || bad "secret in output"

grep -q "^delete clients/c-1/default-client-scopes/ds-1 " "$c" && [ "$(grep -c "^delete clients/c-1/default-client-scopes/" "$c")" -eq 1 ] && ! grep -q platform-api-audience "$S1/dscopes" && grep -q "^ds-2,profile" "$S1/dscopes" && grep -q "^ds-3,roles" "$S1/dscopes" && ok "fresh: platform-api-audience is removed from the client's default scopes, nothing else" || bad "fresh: default scope handling"

grep -q "create clients.*enabled=false" "$c" && [ "$(cat "$S1/enabled")" = true ] && [ "$(grep -n 'enabled=true' "$c" | head -1 | cut -d: -f1)" -gt "$(grep -n 'authenticationFlowBindingOverrides.browser=f-' "$c" | tail -1 | cut -d: -f1)" ] && ok "fresh: client is created DISABLED and enabled only after the flow is bound" || bad "fresh: fail-closed enable order"

# 2. re-run on the verified state changes nothing
cp -r "$S1" "$T/rerun"; S2="$T/rerun"; : > "$S2/calls"; run "$S2"
[ $RC -eq 0 ] && grep -q "present and verified" <<<"$OUT" && ok "re-run: flow is verified and reported present" || bad "re-run: $OUT"
! grep -q "create authentication/flows\|delete authentication" "$S2/calls" && ok "re-run: no flow is created or deleted" || bad "re-run changed the flow"
! grep -q "^delete clients/c-1/default-client-scopes/" "$S2/calls" && ok "re-run: no default scope is touched again" || bad "re-run deleted a default scope again"

# 3. half-built flow (OTP missing): rebuilt completely, bound only after verification
cp -r "$S1" "$T/half"; S3="$T/half"; : > "$S3/calls"; grep -v "auth-otp-form" "$S3/execs" > "$S3/execs.n"; mv "$S3/execs.n" "$S3/execs"; run "$S3"
[ $RC -eq 0 ] && grep -q "NOT as expected" <<<"$OUT" && grep -q "^deleted" "$S3/calls" && ok "half-built flow: detected, deleted, rebuilt" || bad "half-built: rc=$RC $OUT"
grep -q "provider=auth-otp-form" "$S3/calls" && ok "half-built: the OTP step is back" || bad "half-built: no OTP"
u=$(grep -n 'authenticationFlowBindingOverrides.browser= ' "$S3/calls" | head -1 | cut -d: -f1); [ -z "$u" ] && u=$(grep -n 'authenticationFlowBindingOverrides.browser=$' "$S3/calls" | head -1 | cut -d: -f1)
bindl=$(grep -n 'authenticationFlowBindingOverrides.browser=f-' "$S3/calls" | tail -1 | cut -d: -f1)
[ -n "$u" ] && [ -n "$bindl" ] && [ "$u" -lt "$bindl" ] && ok "half-built: unbound first, bound again only at the end" || bad "half-built: unbind/bind order ($u/$bindl)"

d1=$(grep -n 'enabled=false' "$S3/calls" | head -1 | cut -d: -f1); d2=$(grep -n '^deleted' "$S3/calls" | head -1 | cut -d: -f1)
[ -n "$d1" ] && [ -n "$d2" ] && [ "$d1" -lt "$d2" ] && ok "half-built: the client is disabled BEFORE the flow is unbound and deleted" || bad "half-built: fail-closed order ($d1/$d2)"
# 3b. a kcadm error while verifying is an error, not drift: stop, change nothing
cp -r "$S1" "$T/vfail"; S6="$T/vfail"; : > "$S6/calls"; touch "$S6/exec-get-fails"; run "$S6"
[ $RC -ne 0 ] && grep -q "kcadm error" <<<"$OUT" && ! grep -q "^deleted\|enabled=false\|create authentication/flows " "$S6/calls" && ok "a kcadm error during verification stops the run (no delete, no disable, no rebuild)" || bad "verify error treated as drift (rc=$RC)"

# 4. a flow whose role gate is NOT negated is also rejected (the inverted-gate mutation)
cp -r "$S1" "$T/inv"; S4="$T/inv"; : > "$S4/calls"; sed -i 's/negate=true/negate=false/' "$S4"/cfg-*; run "$S4"
grep -q "NOT as expected" <<<"$OUT" && ok "an inverted role gate (negate=false) is detected" || bad "inverted gate accepted"

# 5. a failing `get clients` stops the run: no duplicate create, no false 'created'
S5="$(mk getfail)"; touch "$S5/get-clients-fails"; run "$S5"
[ $RC -ne 0 ] && ! grep -q "create clients" "$S5/calls" && ! grep -q "client ops-kafbat created" <<<"$OUT" && ok "a failing client lookup aborts (no create, no false success)" || bad "get clients failure treated as absent (rc=$RC)"

# 6. Row 543 - login: client credentials by default, admin password only as the explicit fallback
S7="$(mk loginclient)"; run "$S7"
l="$(grep '^config credentials' "$S7/calls" | head -1)"
[ $RC -eq 0 ] && grep -q -- "--client ipass-kcadm" <<<"$l" && grep -q -- "--secret" <<<"$l" && ! grep -q -- "--user\|--password" <<<"$l" && ok "login: default is the client ipass-kcadm with a secret, no --user/--password" || bad "login default (rc=$RC): $l"
! grep -qE "(^| )cs( |$)" <<<"$OUT" && ok "login: the client secret is not printed" || bad "client secret in output"
tail -1 "$S7/calls" | grep -q "config truncate" && ok "login: the kcadm token (and stored secret) is truncated at exit" || bad "login: no config truncate at exit"
S8="$(mk loginid)"; RUN_ENV=(KC_CLIENT_ID=my-client); run "$S8"; RUN_ENV=()
grep -q -- "--client my-client" "$S8/calls" && ok "login: KC_CLIENT_ID overrides the client id" || bad "login: client id override"
S9="$(mk loginpw)"; RUN_ENV=(KC_AUTH=password KC_ADMIN_USERNAME=u KC_ADMIN_PASSWORD=p); run "$S9"; RUN_ENV=()
l="$(grep '^config credentials' "$S9/calls" | head -1)"
[ $RC -eq 0 ] && grep -q -- "--user u" <<<"$l" && grep -q -- "--password p" <<<"$l" && ! grep -q -- "--client" <<<"$l" && ok "login: KC_AUTH=password is the explicit admin-password fallback" || bad "login fallback (rc=$RC): $l"
S10="$(mk nosecret)"; RUN_ENV=(KC_CLIENT_SECRET=); run "$S10"; RUN_ENV=()
[ $RC -ne 0 ] && ! grep -q "create roles\|create clients" "$S10/calls" && ok "login: client mode without KC_CLIENT_SECRET stops before any change" || bad "login: missing secret not refused (rc=$RC)"
S11="$(mk nopw)"; RUN_ENV=(KC_AUTH=password); run "$S11"; RUN_ENV=()
[ $RC -ne 0 ] && ! grep -q "create roles\|create clients" "$S11/calls" && ok "login: password mode without user/password stops before any change" || bad "login: password mode not refused (rc=$RC)"
S12="$(mk badauth)"; RUN_ENV=(KC_AUTH=bogus); run "$S12"; RUN_ENV=()
[ $RC -ne 0 ] && ! grep -q "^config credentials" "$S12/calls" && ok "login: an unknown KC_AUTH value is refused (no silent fallback)" || bad "login: unknown KC_AUTH accepted"

echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"; exit $fail
