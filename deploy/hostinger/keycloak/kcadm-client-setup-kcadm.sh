#!/bin/bash
# Runs INSIDE the keycloak container (fed by kcadm-client-setup.sh through `bash -s`); also run directly by
# test/kcadm-client-setup-stub.sh with KCADM pointing at a stub. Row 543.
# Creates (or re-checks) the dedicated MASTER-realm client the repo scripts log in with instead of the named admin's password:
#   client  ipass-kcadm   confidential, service account ON, client-credentials ONLY (no browser flow, no password grant, no implicit).
#   roles   the service account gets exactly these client roles and no others (drift is removed, a missing one is added):
#             platform-realm: manage-clients manage-realm manage-users view-clients view-realm view-users
#               (ops-setup: roles, client, scope mappings, mappers, flows; rotate-secret: client secrets; seed-showcase: users, passwords)
#             master-realm (ONLY with WITH_MASTER_USERS=1, for retire-bootstrap-admin; remove again afterwards): manage-users view-users
#           Never realm-admin / the realm-management 'all' composite.
# Environment (names only, never printed): KC_ADMIN_USERNAME + KC_ADMIN_PASSWORD (the one-time admin login that creates the client; run
# this BEFORE the admin enrols TOTP, or use the console), KC_CLIENT_ID (default ipass-kcadm), KC_NEW_CLIENT_SECRET (needed to create the
# client or when ROTATE=1; at least 32 characters; yours, from a password manager - never generated into a file), ROTATE=1, WITH_MASTER_USERS=1.
# Safe to re-run: an existing client keeps its secret unless ROTATE=1. When a new secret was set, it is proven by a real client-credentials
# login and one call into realm platform before the script ends.
set -euo pipefail
shopt -s inherit_errexit 2>/dev/null || true
K="${KCADM:-/opt/keycloak/bin/kcadm.sh}"
: "${KC_ADMIN_USERNAME:?}" "${KC_ADMIN_PASSWORD:?}"
ID="${KC_CLIENT_ID:-ipass-kcadm}"
NEWSEC="${KC_NEW_CLIENT_SECRET:-}"
SRV="${KC_SERVER:-http://localhost:8080}"
SA="service-account-$ID"
trap 'rm -f "${HOME:-/opt/keycloak}/.keycloak/kcadm.config"' EXIT   # kcadm 26.8.1 has no `config truncate`: delete the config file (token, stored secret)
crlf() { tr -d '\r'; }
list() { local o; o="$("$K" "$@")" || return 1; printf '%s\n' "$o" | crlf; }
PLATFORM_ROLES="manage-clients manage-realm manage-users view-clients view-realm view-users"
MASTER_ROLES=""; [ "${WITH_MASTER_USERS:-0}" = 1 ] && MASTER_ROLES="manage-users view-users"

"$K" config credentials --server "$SRV" --realm master --user "$KC_ADMIN_USERNAME" --password "$KC_ADMIN_PASSWORD" >/dev/null
CID="$(list get clients -r master -q "clientId=$ID" --fields id --format csv --noquotes | sed -n 1p)"
SET_SECRET=0
if [ -z "$CID" ]; then
  [ "${#NEWSEC}" -ge 32 ] || { echo "KC_NEW_CLIENT_SECRET (at least 32 characters) is needed to create $ID" >&2; exit 2; }
  CID="$("$K" create clients -r master -i -s "clientId=$ID" -s 'name=Repo scripts kcadm login (service account)' -s enabled=true -s protocol=openid-connect \
    -s publicClient=false -s serviceAccountsEnabled=true -s standardFlowEnabled=false -s implicitFlowEnabled=false -s directAccessGrantsEnabled=false \
    -s clientAuthenticatorType=client-secret -s 'redirectUris=[]' -s 'webOrigins=[]' -s "secret=$NEWSEC" | crlf)"
  [ -n "$CID" ] || { echo "client id not returned" >&2; exit 1; }
  echo "client $ID created (service account, client credentials only)"; SET_SECRET=1
elif [ "${ROTATE:-0}" = 1 ]; then
  [ "${#NEWSEC}" -ge 32 ] || { echo "ROTATE=1 needs KC_NEW_CLIENT_SECRET (at least 32 characters)" >&2; exit 2; }
  "$K" update "clients/$CID" -r master -s "secret=$NEWSEC" >/dev/null
  echo "client $ID secret rotated"; SET_SECRET=1
else
  echo "client $ID present, secret unchanged"
fi


# M3: read the security-relevant settings back (new AND existing client) and repair drift. A value that cannot be read back counts as drift.
FLAGS=(enabled=true publicClient=false serviceAccountsEnabled=true standardFlowEnabled=false implicitFlowEnabled=false
  directAccessGrantsEnabled=false 'clientAuthenticatorType="client-secret"' 'redirectUris=[]' 'webOrigins=[]')
DRIFT=(); FIX=()
read_drift() {
  local p f want got; DRIFT=(); FIX=()
  for p in "${FLAGS[@]}"; do
    f="${p%%=*}"; want="${p#*=}"
    got="$(list get "clients/$CID" -r master --fields "$f" | tr -d ' \n')" || { echo "kcadm error reading $f of client $ID" >&2; exit 1; }
    if [ "$got" != "{\"$f\":$want}" ]; then DRIFT+=("$f"); FIX+=(-s "$f=${want//\"/}"); fi
  done
}
read_drift
if [ "${#DRIFT[@]}" -gt 0 ]; then
  "$K" update "clients/$CID" -r master "${FIX[@]}" >/dev/null
  echo "client $ID drift repaired: ${DRIFT[*]}"
  read_drift
  [ "${#DRIFT[@]}" -eq 0 ] || { echo "client $ID still differs after the repair: ${DRIFT[*]}" >&2; exit 1; }
fi

CHANGED=0; MASTER_DROPPED=0
sync_roles() { # target-client roles-wanted-as-words : adds the missing, removes the extra
  local cc="$1" want=" $2 " have r
  have="$(list get-roles -r master --uusername "$SA" --cclientid "$cc" --fields name --format csv --noquotes)" || { echo "kcadm error reading roles of $SA on $cc" >&2; exit 1; }
  local add=() del=()
  for r in $2; do grep -qxF -- "$r" <<<"$have" || add+=(--rolename "$r"); done
  while IFS= read -r r; do [ -z "$r" ] && continue; case "$want" in *" $r "*) ;; *) del+=(--rolename "$r");; esac; done <<<"$have"
  if [ "${#add[@]}" -gt 0 ]; then "$K" add-roles -r master --uusername "$SA" --cclientid "$cc" "${add[@]}"; echo "$cc: granted ${add[*]//--rolename/}"; CHANGED=1; fi
  if [ "${#del[@]}" -gt 0 ]; then "$K" remove-roles -r master --uusername "$SA" --cclientid "$cc" "${del[@]}"; echo "$cc: removed ${del[*]//--rolename/}"; CHANGED=1; if [ "$cc" = master-realm ]; then MASTER_DROPPED=1; fi; fi
}
sync_roles platform-realm "$PLATFORM_ROLES"
sync_roles master-realm "$MASTER_ROLES"
if [ "$SET_SECRET" = 0 ] && [ "$CHANGED" = 0 ]; then echo "roles unchanged"; fi
if [ "$SET_SECRET" = 0 ] && [ "$CHANGED" = 1 ]; then echo "roles reconciled"; fi

if [ "$SET_SECRET" = 1 ]; then   # prove the new secret end to end: a client-credentials login, then a call into realm platform
  rm -f "${HOME:-/opt/keycloak}/.keycloak/kcadm.config"
  "$K" config credentials --server "$SRV" --realm master --client "$ID" --secret "$NEWSEC" >/dev/null || { echo "the new client secret does NOT log in" >&2; exit 1; }
  "$K" get realms/platform --fields realm >/dev/null || { echo "client login works but realm platform is not readable (roles?)" >&2; exit 1; }
  echo "verified: client-credentials login works and realm platform is readable"
fi
echo "kcadm-client-setup done"
if [ -n "$MASTER_ROLES" ]; then
  echo "REMINDER: $SA now holds master-realm manage-users/view-users (a master takeover primitive). Order, all BEFORE the named admin enrols TOTP:"
  echo "  1. keycloak/retire-bootstrap-admin.sh   2. this script again WITHOUT --with-master-users   3. this script with --rotate   4. only then TOTP"
fi
if [ "$MASTER_DROPPED" = 1 ]; then
  echo "REMINDER: the master-realm roles are gone, but the OLD secret held master power: rotate it now (--rotate with a NEW secret) BEFORE the named admin enrols TOTP (after TOTP this script cannot log in; use the console)."
fi
