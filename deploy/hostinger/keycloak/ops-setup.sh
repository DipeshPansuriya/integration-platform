#!/bin/sh
# T219 - creates, in the Keycloak realm `platform`, what the operator host (ops.<domain>) needs. Run on the server in the
# project directory, with the stack up:   ./keycloak/ops-setup.sh        (DRY_RUN=1 prints the plan only)
#   1. realm role  ops-operator                       (no user is created or assigned: that is the owner's step)
#   2. client      ops-kafbat   confidential, authorization-code only, full scope OFF, only the ops-operator role mapped,
#                  `roles` claim, exact redirect URI https://ops.<domain>/kafka/login/oauth2/code/keycloak
#   3. flow        ops-browser  username+password -> deny anyone WITHOUT ops-operator -> OTP (required; a user with no
#                  authenticator is made to set one up). No SSO-cookie shortcut, so an existing platform session never
#                  skips the role check or the one-time code. Bound to the client ops-kafbat ONLY (platform logins unchanged).
# Idempotent. Credentials: the named master admin in ./.env.kc-admin (KC_ADMIN_USERNAME, KC_ADMIN_PASSWORD) and
# OPS_KAFBAT_CLIENT_SECRET in ./.env (generated here when missing). Values travel as environment NAMES and are never printed.
set -eu
cd "$(dirname "$0")/.."
ENV_FILE="${ENV_FILE:-./.env}"
ADMIN_FILE="${KC_ADMIN_FILE:-./.env.kc-admin}"
COMPOSE="${COMPOSE:-docker compose}"
if [ "${DRY_RUN:-0}" = 1 ]; then
  echo "would read $ENV_FILE and $ADMIN_FILE (names only), generate OPS_KAFBAT_CLIENT_SECRET if missing,"
  echo "then in realm platform: ensure role ops-operator, client ops-kafbat, mapper roles, flow ops-browser (password, role gate, OTP), bind the flow to the client"
  exit 0
fi
umask 077
if ! grep -q '^OPS_KAFBAT_CLIENT_SECRET=.' "$ENV_FILE"; then
  v="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-40)"
  printf 'OPS_KAFBAT_CLIENT_SECRET=%s\n' "$v" >> "$ENV_FILE"
  echo "OPS_KAFBAT_CLIENT_SECRET generated, fingerprint $(printf '%s' "$v" | openssl dgst -sha256 | sed 's/^.*= *//' | cut -c1-8)"
fi
set -a; . "$ENV_FILE"; . "$ADMIN_FILE"; set +a
: "${IPASS_DOMAIN:?}" "${KC_ADMIN_USERNAME:?}" "${KC_ADMIN_PASSWORD:?}" "${OPS_KAFBAT_CLIENT_SECRET:?}"
export IPASS_DOMAIN KC_ADMIN_USERNAME KC_ADMIN_PASSWORD OPS_KAFBAT_CLIENT_SECRET

$COMPOSE exec -T -e IPASS_DOMAIN -e KC_ADMIN_USERNAME -e KC_ADMIN_PASSWORD -e OPS_KAFBAT_CLIENT_SECRET keycloak sh -s <<'INNER'
set -eu
K=/opt/keycloak/bin/kcadm.sh
R=platform
$K config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN_USERNAME" --password "$KC_ADMIN_PASSWORD" >/dev/null
csv() { tr -d '\r'; }
REDIR="https://ops.${IPASS_DOMAIN}/kafka/login/oauth2/code/keycloak"

# 1. role
if ! $K get roles/ops-operator -r $R >/dev/null 2>&1; then
  $K create roles -r $R -s name=ops-operator -s 'description=Operator address (ops host): Kafbat read-only view. Named accounts only; OTP required.' >/dev/null
  echo "role ops-operator created"
else echo "role ops-operator present"; fi
ROLE_ID="$($K get roles/ops-operator -r $R --fields id --format csv --noquotes | csv)"

# 2. client
CID="$($K get clients -r $R -q clientId=ops-kafbat --fields id --format csv --noquotes | csv | head -1)"
if [ -z "$CID" ]; then
  CID="$($K create clients -r $R -i \
    -s clientId=ops-kafbat -s name='Operator Kafka view (Kafbat)' -s enabled=true -s protocol=openid-connect \
    -s publicClient=false -s standardFlowEnabled=true -s implicitFlowEnabled=false -s directAccessGrantsEnabled=false \
    -s serviceAccountsEnabled=false -s fullScopeAllowed=false -s clientAuthenticatorType=client-secret \
    -s "secret=$OPS_KAFBAT_CLIENT_SECRET" -s "rootUrl=https://ops.${IPASS_DOMAIN}" \
    -s "redirectUris=[\"$REDIR\"]" -s 'webOrigins=[]' -s "attributes.\"post.logout.redirect.uris\"=https://ops.${IPASS_DOMAIN}/kafka/" | csv)"
  echo "client ops-kafbat created"
else
  $K update "clients/$CID" -r $R -s "secret=$OPS_KAFBAT_CLIENT_SECRET" -s fullScopeAllowed=false -s directAccessGrantsEnabled=false \
    -s "redirectUris=[\"$REDIR\"]" -s 'webOrigins=[]' >/dev/null
  echo "client ops-kafbat present (secret, redirect, scope reset)"
fi
# only this role may ever appear in the client's tokens
$K create "clients/$CID/scope-mappings/realm" -r $R -b "[{\"id\":\"$ROLE_ID\",\"name\":\"ops-operator\"}]" >/dev/null 2>&1 || true
if [ -z "$($K get "clients/$CID/protocol-mappers/models" -r $R --fields name --format csv --noquotes | csv | grep -x roles || true)" ]; then
  $K create "clients/$CID/protocol-mappers/models" -r $R -s name=roles -s protocol=openid-connect \
    -s protocolMapper=oidc-usermodel-realm-role-mapper -s 'config."claim.name"=roles' -s 'config."jsonType.label"=String' \
    -s 'config.multivalued=true' -s 'config."id.token.claim"=true' -s 'config."access.token.claim"=true' \
    -s 'config."userinfo.token.claim"=true' >/dev/null
  echo "mapper roles created"
fi

# 3. flow
FLOW_ID="$($K get authentication/flows -r $R --fields id,alias --format csv --noquotes | csv | grep ',ops-browser$' | cut -d, -f1 || true)"
if [ -z "$FLOW_ID" ]; then
  $K create authentication/flows -r $R -s alias=ops-browser -s providerId=basic-flow -s topLevel=true -s builtIn=false \
    -s 'description=ops host login: password, ops-operator role gate, OTP (no cookie shortcut)' >/dev/null
  req() { # flow-alias  execution-displayName-or-providerId  requirement  -> prints the execution id
    id="$($K get "authentication/flows/$1/executions" -r $R --fields id,displayName,providerId --format csv --noquotes | csv | grep -E ",$2(,|\$)|,$2\$" | head -1 | cut -d, -f1)"
    [ -n "$id" ] || { echo "execution $2 not found in $1" >&2; exit 1; }
    $K update "authentication/flows/$1/executions" -r $R -b "{\"id\":\"$id\",\"requirement\":\"$3\"}" >/dev/null
    echo "$id"
  }
  $K create authentication/flows/ops-browser/executions/flow -r $R -s alias=ops-browser-forms -s type=basic-flow -s provider=registration-page-form >/dev/null
  req ops-browser ops-browser-forms REQUIRED >/dev/null
  $K create authentication/flows/ops-browser-forms/executions/execution -r $R -s provider=auth-username-password-form >/dev/null
  req ops-browser-forms auth-username-password-form REQUIRED >/dev/null
  $K create authentication/flows/ops-browser-forms/executions/flow -r $R -s alias=ops-role-gate -s type=basic-flow -s provider=registration-page-form >/dev/null
  req ops-browser-forms ops-role-gate CONDITIONAL >/dev/null
  $K create authentication/flows/ops-role-gate/executions/execution -r $R -s provider=conditional-user-role >/dev/null
  $K create authentication/flows/ops-role-gate/executions/execution -r $R -s provider=deny-access-authenticator >/dev/null
  COND="$(req ops-role-gate conditional-user-role REQUIRED)"
  req ops-role-gate deny-access-authenticator REQUIRED >/dev/null
  $K create "authentication/executions/$COND/config" -r $R -s alias=ops-operator-absent \
    -s 'config.condUserRole=ops-operator' -s 'config.negate=true' >/dev/null
  $K create authentication/flows/ops-browser-forms/executions/execution -r $R -s provider=auth-otp-form >/dev/null
  req ops-browser-forms auth-otp-form REQUIRED >/dev/null
  FLOW_ID="$($K get authentication/flows -r $R --fields id,alias --format csv --noquotes | csv | grep ',ops-browser$' | cut -d, -f1)"
  echo "flow ops-browser created"
else echo "flow ops-browser present"; fi
$K update "clients/$CID" -r $R -s "authenticationFlowBindingOverrides={\"browser\":\"$FLOW_ID\"}" >/dev/null
echo "flow bound to client ops-kafbat only"
INNER
echo "ops-setup done (no user created: assign role ops-operator to the named operator accounts)"
