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
    -s clientId=ops-kafbat -s name='Operator Kafka view (Kafbat)' -s enabled=true -s protocol=openid-connect \
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
flow_id() { list get authentication/flows -r $R --fields id,alias --format csv --noquotes | awk -F, '$2=="ops-browser"{print $1; exit}'; }

# the flow is right only if it holds exactly these executions, in this order inside ops-browser-forms, and the role gate
# is NEGATED (deny anyone WITHOUT ops-operator)
verify_flow() {
  local rows cfg
  rows="$(list get authentication/flows/ops-browser/executions -r $R --fields displayName,providerId,requirement,level,authenticationConfig --format csv --noquotes)" || return 1
  [ "$(printf '%s\n' "$rows" | awk -F, '{printf "%s|%s|%s|%s\n",$1,$2,$3,$4}' | LC_ALL=C sort)" = "$(printf '%s\n' \
    'Condition - user role|conditional-user-role|REQUIRED|2' \
    'Deny access|deny-access-authenticator|REQUIRED|2' \
    'OTP Form|auth-otp-form|REQUIRED|1' \
    'Username Password Form|auth-username-password-form|REQUIRED|1' \
    'ops-browser-forms||REQUIRED|0' \
    'ops-role-gate||CONDITIONAL|1' | LC_ALL=C sort)" ] || return 1
  [ "$(printf '%s\n' "$rows" | awk -F, '$4==1{printf "%s ", $2$1}')" = "auth-username-password-formUsername Password Form ops-role-gate auth-otp-formOTP Form " ] || return 1
  cfg="$(printf '%s\n' "$rows" | awk -F, '$2=="conditional-user-role"{print $5; exit}')"
  [ -n "$cfg" ] || return 1
  list get "authentication/config/$cfg" -r $R | tr -d ' ' | grep '"condUserRole":"ops-operator"' >/dev/null || return 1
  list get "authentication/config/$cfg" -r $R | tr -d ' ' | grep '"negate":"true"' >/dev/null || return 1
}

req() { # flow-alias  displayName-or-providerId  requirement  -> prints the execution id
  local id
  id="$(list get "authentication/flows/$1/executions" -r $R --fields id,displayName,providerId --format csv --noquotes | awk -F, -v w="$2" '$2==w||$3==w{print $1; exit}')"
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
if [ -n "$FID" ] && verify_flow; then
  echo "flow ops-browser present and verified"
else
  if [ -n "$FID" ]; then
    echo "flow ops-browser present but NOT as expected - unbinding, deleting, rebuilding"
    "$K" update "clients/$CID" -r $R -s 'authenticationFlowBindingOverrides.browser=' >/dev/null
    "$K" delete "authentication/flows/$FID" -r $R >/dev/null
  fi
  build_flow
  verify_flow || { echo "flow ops-browser failed verification after the build - NOT bound to the client" >&2; exit 1; }
  FID="$(flow_id)"
  echo "flow ops-browser built and verified"
fi
# bind only now, after the flow verified
"$K" update "clients/$CID" -r $R -s "authenticationFlowBindingOverrides.browser=$FID" >/dev/null
list get "clients/$CID" -r $R | tr -d ' ' | grep "\"browser\":\"$FID\"" >/dev/null || { echo "binding did not stick" >&2; exit 1; }
echo "flow bound to client ops-kafbat only"
