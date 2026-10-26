#!/bin/bash
# Runs INSIDE the keycloak container (fed by ops-setup.sh through `bash -s`); also run directly by test/ops-setup-stub.sh
# with KCADM pointing at a stub. Environment (names only, never printed): IPASS_DOMAIN, KC_ADMIN_USERNAME,
# KC_ADMIN_PASSWORD, OPS_KAFBAT_CLIENT_SECRET. Builds, in realm `platform`: role ops-operator, client ops-kafbat, the
# `roles` mapper, and the browser flow ops-browser (password -> deny anyone WITHOUT ops-operator -> OTP). Safe to re-run:
# the flow is verified on every run, rebuilt completely when it has drifted, and bound to the client ONLY after it verifies.
set -euo pipefail
shopt -s inherit_errexit 2>/dev/null || true
K="${KCADM:-/opt/keycloak/bin/kcadm.sh}"
R=platform
: "${IPASS_DOMAIN:?}" "${KC_ADMIN_USERNAME:?}" "${KC_ADMIN_PASSWORD:?}" "${OPS_KAFBAT_CLIENT_SECRET:?}"
trap '"$K" config truncate >/dev/null 2>&1 || true' EXIT   # no admin token left in the container's writable layer
"$K" config credentials --server "${KC_SERVER:-http://localhost:8080}" --realm master --user "$KC_ADMIN_USERNAME" --password "$KC_ADMIN_PASSWORD" >/dev/null
REDIR="https://ops.${IPASS_DOMAIN}/kafka/login/oauth2/code/keycloak"
crlf() { tr -d '\r'; }
# capture kcadm output FIRST (so a failing call stops the script), then parse it
list() { local o; o="$("$K" "$@")" || return 1; printf '%s\n' "$o" | crlf; }

# 1. role
if list get roles -r $R --fields name --format csv --noquotes | grep -x ops-operator >/dev/null; then echo "role ops-operator present"
else "$K" create roles -r $R -s name=ops-operator -s 'description=Operator address (ops host): Kafbat read-only view. Named accounts only; OTP required.' >/dev/null; echo "role ops-operator created"; fi
ROLE_ID="$(list get roles/ops-operator -r $R --fields id --format csv --noquotes | sed -n 1p)"
[ -n "$ROLE_ID" ] || { echo "role id not found" >&2; exit 1; }

# 2. client
CID="$(list get clients -r $R -q clientId=ops-kafbat --fields id --format csv --noquotes | sed -n 1p)"
if [ -z "$CID" ]; then
  CID="$("$K" create clients -r $R -i \
    -s clientId=ops-kafbat -s name='Operator Kafka view (Kafbat)' -s enabled=false -s protocol=openid-connect \
    -s publicClient=false -s standardFlowEnabled=true -s implicitFlowEnabled=false -s directAccessGrantsEnabled=false \
    -s serviceAccountsEnabled=false -s fullScopeAllowed=false -s clientAuthenticatorType=client-secret \
    -s "secret=$OPS_KAFBAT_CLIENT_SECRET" -s "rootUrl=https://ops.${IPASS_DOMAIN}" \
    -s "redirectUris=[\"$REDIR\"]" -s 'webOrigins=[]' -s "attributes.\"post.logout.redirect.uris\"=https://ops.${IPASS_DOMAIN}/kafka/" | crlf)"
  [ -n "$CID" ] || { echo "client create returned no id" >&2; exit 1; }
  echo "client ops-kafbat created"
else
  "$K" update "clients/$CID" -r $R -s "secret=$OPS_KAFBAT_CLIENT_SECRET" -s fullScopeAllowed=false -s directAccessGrantsEnabled=false \
    -s "redirectUris=[\"$REDIR\"]" -s 'webOrigins=[]' >/dev/null
  echo "client ops-kafbat present (secret, redirect, scope reset)"
fi
# the realm's default scope platform-api-audience would put the platform API audience into the ops-kafbat tokens (Kafbat holds
# them server-side): remove it from this client only (idempotent, checked after). Other default scopes stay.
rows="$(list get "clients/$CID/default-client-scopes" -r $R --fields id,name --format csv --noquotes)"
while IFS=, read -r sid sname; do
  if [ "$sname" = platform-api-audience ]; then
    "$K" delete "clients/$CID/default-client-scopes/$sid" -r $R >/dev/null
    echo "default scope platform-api-audience removed from ops-kafbat"
  fi
done <<<"$rows"
if list get "clients/$CID/default-client-scopes" -r $R --fields name --format csv --noquotes | grep -x platform-api-audience >/dev/null; then
  echo "platform-api-audience still a default scope of ops-kafbat" >&2; exit 1
fi
# only this role may ever appear in the client's tokens (checked, not assumed)
if ! list get "clients/$CID/scope-mappings/realm" -r $R --fields name --format csv --noquotes | grep -x ops-operator >/dev/null; then
  "$K" create "clients/$CID/scope-mappings/realm" -r $R -b "[{\"id\":\"$ROLE_ID\",\"name\":\"ops-operator\"}]" >/dev/null
  list get "clients/$CID/scope-mappings/realm" -r $R --fields name --format csv --noquotes | grep -x ops-operator >/dev/null \
    || { echo "scope mapping ops-operator missing after create" >&2; exit 1; }
fi
if ! list get "clients/$CID/protocol-mappers/models" -r $R --fields name --format csv --noquotes | grep -x roles >/dev/null; then
  "$K" create "clients/$CID/protocol-mappers/models" -r $R -s name=roles -s protocol=openid-connect \
    -s protocolMapper=oidc-usermodel-realm-role-mapper -s 'config."claim.name"=roles' -s 'config."jsonType.label"=String' \
    -s 'config.multivalued=true' -s 'config."id.token.claim"=true' -s 'config."access.token.claim"=true' \
    -s 'config."userinfo.token.claim"=true' >/dev/null
  echo "mapper roles created"
fi

# 3. flow
flow_id() { # the container has no awk: bash only
  local rows id alias; rows="$(list get authentication/flows -r $R --fields id,alias --format csv --noquotes)" || return 1
  while IFS=, read -r id alias; do [ "$alias" = ops-browser ] && { echo "$id"; return 0; }; done <<<"$rows"; return 0
}

# the flow is right only if it holds exactly these executions, in this order inside ops-browser-forms, and the role gate
# is NEGATED (deny anyone WITHOUT ops-operator)
verify_flow() {
  local rows nm prov rq lv cfg condcfg="" seen="" l1="" line
  rows="$(list get authentication/flows/ops-browser/executions -r $R --fields displayName,providerId,requirement,level,authenticationConfig --format csv --noquotes)" || return 2   # 2 = kcadm error, 1 = drift
  while IFS=, read -r nm prov rq lv cfg; do
    [ -n "$nm$prov$rq$lv" ] || continue
    seen+="$nm|$prov|$rq|$lv"$'
'
    [ "$lv" = 1 ] && l1+="$prov$nm "
    [ "$prov" = conditional-user-role ] && condcfg="$cfg"
  done <<<"$rows"
  [ "$(printf '%s' "$seen" | LC_ALL=C sort)" = "$(printf '%s
'     'Condition - user role|conditional-user-role|REQUIRED|2'     'Deny access|deny-access-authenticator|REQUIRED|2'     'OTP Form|auth-otp-form|REQUIRED|1'     'Username Password Form|auth-username-password-form|REQUIRED|1'     'ops-browser-forms||REQUIRED|0'     'ops-role-gate||CONDITIONAL|1' | LC_ALL=C sort)" ] || return 1
  [ "$l1" = "auth-username-password-formUsername Password Form ops-role-gate auth-otp-formOTP Form " ] || return 1
  [ -n "$condcfg" ] || return 1
  line="$(list get "authentication/config/$condcfg" -r $R | tr -d ' ')" || return 2
  grep '"condUserRole":"ops-operator"' >/dev/null <<<"$line" || return 1
  grep '"negate":"true"' >/dev/null <<<"$line" || return 1
}

req() { # flow-alias  displayName-or-providerId  requirement  -> prints the execution id
  local id="" rows i nm prov
  rows="$(list get "authentication/flows/$1/executions" -r $R --fields id,displayName,providerId --format csv --noquotes)" || exit 1
  while IFS=, read -r i nm prov; do [ "$nm" = "$2" ] || [ "$prov" = "$2" ] && { id="$i"; break; }; done <<<"$rows"
  [ -n "$id" ] || { echo "execution $2 not found in $1" >&2; exit 1; }
  "$K" update "authentication/flows/$1/executions" -r $R -b "{\"id\":\"$id\",\"requirement\":\"$3\"}" >/dev/null
  echo "$id"
}

build_flow() {
  "$K" create authentication/flows -r $R -s alias=ops-browser -s providerId=basic-flow -s topLevel=true -s builtIn=false \
    -s 'description=ops host login: password, ops-operator role gate, OTP (no cookie shortcut)' >/dev/null
  "$K" create authentication/flows/ops-browser/executions/flow -r $R -s alias=ops-browser-forms -s type=basic-flow -s provider=registration-page-form >/dev/null
  req ops-browser ops-browser-forms REQUIRED >/dev/null
  "$K" create authentication/flows/ops-browser-forms/executions/execution -r $R -s provider=auth-username-password-form >/dev/null
  req ops-browser-forms auth-username-password-form REQUIRED >/dev/null
  "$K" create authentication/flows/ops-browser-forms/executions/flow -r $R -s alias=ops-role-gate -s type=basic-flow -s provider=registration-page-form >/dev/null
  req ops-browser-forms ops-role-gate CONDITIONAL >/dev/null
  "$K" create authentication/flows/ops-role-gate/executions/execution -r $R -s provider=conditional-user-role >/dev/null
  "$K" create authentication/flows/ops-role-gate/executions/execution -r $R -s provider=deny-access-authenticator >/dev/null
  local cond
  cond="$(req ops-role-gate conditional-user-role REQUIRED)"
  req ops-role-gate deny-access-authenticator REQUIRED >/dev/null
  "$K" create "authentication/executions/$cond/config" -r $R -s alias=ops-operator-absent \
    -s 'config.condUserRole=ops-operator' -s 'config.negate=true' >/dev/null
  "$K" create authentication/flows/ops-browser-forms/executions/execution -r $R -s provider=auth-otp-form >/dev/null
  req ops-browser-forms auth-otp-form REQUIRED >/dev/null
}

FID="$(flow_id)"
vrc=0
if [ -n "$FID" ]; then verify_flow || vrc=$?; fi
[ "$vrc" -ne 2 ] || { echo "kcadm error while verifying the flow - stopping, nothing was changed" >&2; exit 1; }   # an error is not drift
if [ -n "$FID" ] && [ "$vrc" -eq 0 ]; then
  echo "flow ops-browser present and verified"
else
  # fail closed: nobody can log in through this client while its flow is unbound or being rebuilt
  "$K" update "clients/$CID" -r $R -s enabled=false >/dev/null
  if [ -n "$FID" ]; then
    echo "flow ops-browser present but NOT as expected - client disabled, unbinding, deleting, rebuilding"
    "$K" update "clients/$CID" -r $R -s 'authenticationFlowBindingOverrides.browser=' >/dev/null
    "$K" delete "authentication/flows/$FID" -r $R >/dev/null
  fi
  build_flow
  rc=0; verify_flow || rc=$?
  [ "$rc" -eq 0 ] || { echo "flow ops-browser failed verification after the build - NOT bound, client stays DISABLED" >&2; exit 1; }
  FID="$(flow_id)"
  echo "flow ops-browser built and verified"
fi
# bind only now, after the flow verified, then (and only then) enable the client
"$K" update "clients/$CID" -r $R -s "authenticationFlowBindingOverrides.browser=$FID" >/dev/null
list get "clients/$CID" -r $R | tr -d ' ' | grep "\"browser\":\"$FID\"" >/dev/null || { echo "binding did not stick - client stays as it is" >&2; exit 1; }
"$K" update "clients/$CID" -r $R -s enabled=true >/dev/null
list get "clients/$CID" -r $R | tr -d ' ' | grep '"enabled":true' >/dev/null || { echo "client could not be enabled" >&2; exit 1; }
echo "flow bound to client ops-kafbat only; client enabled"
