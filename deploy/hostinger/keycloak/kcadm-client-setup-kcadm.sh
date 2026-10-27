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
trap '"$K" config truncate >/dev/null 2>&1 || true' EXIT
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
    -s clientAuthenticatorType=client-secret -s "secret=$NEWSEC" | crlf)"
  [ -n "$CID" ] || { echo "client id not returned" >&2; exit 1; }
  echo "client $ID created (service account, client credentials only)"; SET_SECRET=1
elif [ "${ROTATE:-0}" = 1 ]; then
  [ "${#NEWSEC}" -ge 32 ] || { echo "ROTATE=1 needs KC_NEW_CLIENT_SECRET (at least 32 characters)" >&2; exit 2; }
  "$K" update "clients/$CID" -r master -s "secret=$NEWSEC" >/dev/null
  echo "client $ID secret rotated"; SET_SECRET=1
else
  echo "client $ID present, secret unchanged"
fi

CHANGED=0
sync_roles() { # target-client roles-wanted-as-words : adds the missing, removes the extra
  local cc="$1" want=" $2 " have r
  have="$(list get-roles -r master --uusername "$SA" --cclientid "$cc" --fields name --format csv --noquotes)" || { echo "kcadm error reading roles of $SA on $cc" >&2; exit 1; }
  local add=() del=()
  for r in $2; do grep -qx -- "$r" <<<"$have" || add+=(--rolename "$r"); done
  while IFS= read -r r; do [ -z "$r" ] && continue; case "$want" in *" $r "*) ;; *) del+=(--rolename "$r");; esac; done <<<"$have"
  if [ "${#add[@]}" -gt 0 ]; then "$K" add-roles -r master --uusername "$SA" --cclientid "$cc" "${add[@]}"; echo "$cc: granted ${add[*]//--rolename/}"; CHANGED=1; fi
  if [ "${#del[@]}" -gt 0 ]; then "$K" remove-roles -r master --uusername "$SA" --cclientid "$cc" "${del[@]}"; echo "$cc: removed ${del[*]//--rolename/}"; CHANGED=1; fi
}
sync_roles platform-realm "$PLATFORM_ROLES"
sync_roles master-realm "$MASTER_ROLES"
if [ "$SET_SECRET" = 0 ] && [ "$CHANGED" = 0 ]; then echo "roles unchanged"; fi
if [ "$SET_SECRET" = 0 ] && [ "$CHANGED" = 1 ]; then echo "roles reconciled"; fi

if [ "$SET_SECRET" = 1 ]; then   # prove the new secret end to end: a client-credentials login, then a call into realm platform
  "$K" config truncate >/dev/null 2>&1 || true
  "$K" config credentials --server "$SRV" --realm master --client "$ID" --secret "$NEWSEC" >/dev/null || { echo "the new client secret does NOT log in" >&2; exit 1; }
  "$K" get realms/platform --fields realm >/dev/null || { echo "client login works but realm platform is not readable (roles?)" >&2; exit 1; }
  echo "verified: client-credentials login works and realm platform is readable"
fi
echo "kcadm-client-setup done"
