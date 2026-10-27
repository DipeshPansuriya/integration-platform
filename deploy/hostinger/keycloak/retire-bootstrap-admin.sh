#!/bin/sh
# Retires the temporary Keycloak bootstrap admin (README step 11), in the project folder on the server:
#   1. create a named admin first (master realm, admin role, enabled): the console, or docker compose exec keycloak kcadm.sh ...
#   2. KC_ADMIN_USER=<named admin> ./keycloak/retire-bootstrap-admin.sh   (asks for the client secret with a silent prompt)
#      Do NOT type the secret inline (KC_CLIENT_SECRET=... ./script lands in the shell history). Without a terminal, read it first:
#      read -rs KC_CLIENT_SECRET; export KC_CLIENT_SECRET; ...; unset KC_CLIENT_SECRET
# Row 543: it logs in with the master service-account client ipass-kcadm (created by keycloak/kcadm-client-setup.sh with
# --with-master-users for this step), because the named admin has TOTP and kcadm cannot send a one-time code. The old way - the
# NAMED admin's password prompted by kcadm - is the explicit fallback:  KC_ADMIN_USER=<named admin> ./keycloak/retire-bootstrap-admin.sh --password-login
# Lock-out guard (both modes): KC_ADMIN_USER is required, and the script proves that this named admin exists, is enabled and holds the
# admin role BEFORE it deletes anything. It then deletes the user bootstrap-admin from the master realm, deletes the credential file
# .env.bootstrap-admin, and re-creates keycloak from compose.yaml alone (no bootstrap variables left in the container). DRY_RUN=1 prints the steps.
set -eu
cd "$(dirname "$0")/.."
. ./keycloak/kc-login.sh
kc_parse_flags "$@"; shift "$KC_SHIFT"
COMPOSE="${COMPOSE:-docker compose}"
BOOT_USER=bootstrap-admin
BOOT_FILE="${IPASS_BOOTSTRAP_ADMIN_FILE:-.env.bootstrap-admin}"
K=/opt/keycloak/bin/kcadm.sh
kc_auth_check
: "${KC_ADMIN_USER:?set KC_ADMIN_USER to the named admin that replaces $BOOT_USER (lock-out guard)}"
# Keycloak lower-cases user names, so Bootstrap-Admin IS bootstrap-admin (proof, N1)
[ "$(printf '%s' "$KC_ADMIN_USER" | tr 'A-Z' 'a-z')" != "$BOOT_USER" ] || { echo "KC_ADMIN_USER must be a different user than $BOOT_USER" >&2; exit 2; }
if [ "${DRY_RUN:-0}" = 1 ]; then
  if [ "$KC_AUTH" = client ]; then who="client $KC_CLIENT_ID (secret from KC_CLIENT_SECRET or a silent prompt)"; else who="$KC_ADMIN_USER (password prompted by kcadm)"; fi
  echo "login to master as $who; check that $KC_ADMIN_USER exists, is enabled and has the admin role; delete user $BOOT_USER from realm master; rm $BOOT_FILE; $COMPOSE up -d --no-deps --force-recreate keycloak"
  exit 0
fi
kc_login
# lock-out guard: exact username match (a plain username= query is a substring search), enabled, and the admin role.
# kcadm output is captured FIRST so a failing call (HTTP 403 without the master roles) stops the run for the right reason (proof, 403 test).
kcq() { _o="$($COMPOSE exec -T keycloak $K "$@")" || { echo "cannot read realm master ($*): does $KC_CLIENT_ID hold the master-realm roles manage-users and view-users? (keycloak/kcadm-client-setup.sh --with-master-users); nothing was deleted" >&2; exit 1; }; printf '%s\n' "$_o" | tr -d '\r'; }
out="$(kcq get users -r master -q "username=$KC_ADMIN_USER" -q exact=true --fields id,enabled --format csv --noquotes)" || exit 1
named="$(printf '%s\n' "$out" | head -1)"
case "$named" in ?*,true) ;; ?*,*) echo "named admin $KC_ADMIN_USER is disabled: refusing to delete $BOOT_USER" >&2; exit 1;; *) echo "named admin $KC_ADMIN_USER not found in realm master: refusing to delete $BOOT_USER (it would lock everyone out)" >&2; exit 1;; esac
roles="$(kcq get-roles -r master --uusername "$KC_ADMIN_USER" --effective --fields name --format csv --noquotes)" || exit 1
printf '%s\n' "$roles" | grep -qx 'admin' || { echo "named admin $KC_ADMIN_USER does not hold the admin role: refusing to delete $BOOT_USER" >&2; exit 1; }
out="$(kcq get users -r master -q "username=$BOOT_USER" -q exact=true --fields id --format csv --noquotes)" || exit 1
id="$(printf '%s\n' "$out" | head -1)"
if [ -n "$id" ]; then $COMPOSE exec -T keycloak $K delete "users/$id" -r master; echo "deleted $BOOT_USER"; else echo "$BOOT_USER not present"; fi
kc_logout; trap - EXIT
rm -f "$BOOT_FILE"
$COMPOSE up -d --no-deps --force-recreate keycloak
echo "bootstrap admin retired; keycloak re-created without it"
echo "NEXT, before $KC_ADMIN_USER enrols TOTP: drop the master roles (keycloak/kcadm-client-setup.sh WITHOUT --with-master-users), then rotate the client secret (--rotate: the old one had master power), then enrol TOTP"
