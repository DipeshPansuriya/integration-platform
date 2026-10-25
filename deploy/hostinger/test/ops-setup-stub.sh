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
 "create clients") echo c-1 > "$S/clients"; echo c-1; exit 0;;
 "update clients/c-1")
   case "$args" in *authenticationFlowBindingOverrides.browser=*) b="${args##*authenticationFlowBindingOverrides.browser=}"; b="${b%% *}"; echo "$b" > "$S/bind";; esac; exit 0;;
 "get clients/c-1") echo "{ \"authenticationFlowBindingOverrides\" : { \"browser\" : \"$(cat "$S/bind" 2>/dev/null)\" } }"; exit 0;;
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
 "get authentication/flows/"*"/executions")
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
chmod +x "$T/kcadm"

run() { # scenario-dir -> runs the real script against the stub; sets RC and OUT
  S="$1"; OUT="$(STUB_DIR="$S" KCADM="$T/kcadm" IPASS_DOMAIN=example.test KC_ADMIN_USERNAME=u KC_ADMIN_PASSWORD=p OPS_KAFBAT_CLIENT_SECRET=s bash "$SCRIPT" 2>&1)"; RC=$?; }
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

# 2. re-run on the verified state changes nothing
cp -r "$S1" "$T/rerun"; S2="$T/rerun"; : > "$S2/calls"; run "$S2"
[ $RC -eq 0 ] && grep -q "present and verified" <<<"$OUT" && ok "re-run: flow is verified and reported present" || bad "re-run: $OUT"
! grep -q "create authentication/flows\|delete authentication" "$S2/calls" && ok "re-run: no flow is created or deleted" || bad "re-run changed the flow"

# 3. half-built flow (OTP missing): rebuilt completely, bound only after verification
cp -r "$S1" "$T/half"; S3="$T/half"; : > "$S3/calls"; grep -v "auth-otp-form" "$S3/execs" > "$S3/execs.n"; mv "$S3/execs.n" "$S3/execs"; run "$S3"
[ $RC -eq 0 ] && grep -q "NOT as expected" <<<"$OUT" && grep -q "^deleted" "$S3/calls" && ok "half-built flow: detected, deleted, rebuilt" || bad "half-built: rc=$RC $OUT"
grep -q "provider=auth-otp-form" "$S3/calls" && ok "half-built: the OTP step is back" || bad "half-built: no OTP"
u=$(grep -n 'authenticationFlowBindingOverrides.browser= ' "$S3/calls" | head -1 | cut -d: -f1); [ -z "$u" ] && u=$(grep -n 'authenticationFlowBindingOverrides.browser=$' "$S3/calls" | head -1 | cut -d: -f1)
bindl=$(grep -n 'authenticationFlowBindingOverrides.browser=f-' "$S3/calls" | tail -1 | cut -d: -f1)
[ -n "$u" ] && [ -n "$bindl" ] && [ "$u" -lt "$bindl" ] && ok "half-built: unbound first, bound again only at the end" || bad "half-built: unbind/bind order ($u/$bindl)"

# 4. a flow whose role gate is NOT negated is also rejected (the inverted-gate mutation)
cp -r "$S1" "$T/inv"; S4="$T/inv"; : > "$S4/calls"; sed -i 's/negate=true/negate=false/' "$S4"/cfg-*; run "$S4"
grep -q "NOT as expected" <<<"$OUT" && ok "an inverted role gate (negate=false) is detected" || bad "inverted gate accepted"

# 5. a failing `get clients` stops the run: no duplicate create, no false 'created'
S5="$(mk getfail)"; touch "$S5/get-clients-fails"; run "$S5"
[ $RC -ne 0 ] && ! grep -q "create clients" "$S5/calls" && ! grep -q "client ops-kafbat created" <<<"$OUT" && ok "a failing client lookup aborts (no create, no false success)" || bad "get clients failure treated as absent (rc=$RC)"

echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"; exit $fail
