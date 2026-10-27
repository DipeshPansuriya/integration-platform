#!/bin/sh
# Retires the temporary Keycloak bootstrap admin (README step 11), in the project folder on the server:
#   1. create a named admin first:  docker compose exec keycloak /opt/keycloak/bin/kcadm.sh ...   (master realm, admin role)
#   2. KC_CLIENT_SECRET=<ipass-kcadm secret> ./keycloak/retire-bootstrap-admin.sh        (or be prompted, silently)
# Row 543: it logs in with the master service-account client ipass-kcadm (created by keycloak/kcadm-client-setup.sh with
# WITH_MASTER_USERS=1 for this step), because the named admin has TOTP and kcadm cannot send a one-time code. The old way - the
# NAMED admin's password prompted by kcadm - is the explicit fallback:  KC_ADMIN_USER=<named admin> ./keycloak/retire-bootstrap-admin.sh --password-login
# It deletes the user bootstrap-admin from the master realm, deletes the credential file .env.bootstrap-admin, and re-creates
# keycloak from compose.yaml alone (no bootstrap variables left in the container). DRY_RUN=1 prints the steps.
set -eu
cd "$(dirname "$0")/.."
. ./keycloak/kc-login.sh
kc_parse_flags "$@"; shift "$KC_SHIFT"
COMPOSE="${COMPOSE:-docker compose}"
BOOT_USER=bootstrap-admin
BOOT_FILE="${IPASS_BOOTSTRAP_ADMIN_FILE:-.env.bootstrap-admin}"
K=/opt/keycloak/bin/kcadm.sh
kc_auth_check
if [ "$KC_AUTH" = password ]; then
  : "${KC_ADMIN_USER:?--password-login: set KC_ADMIN_USER to the named admin that replaces $BOOT_USER}"
  [ "$KC_ADMIN_USER" != "$BOOT_USER" ] || { echo "KC_ADMIN_USER must be a different user than $BOOT_USER" >&2; exit 2; }
fi
if [ "${DRY_RUN:-0}" = 1 ]; then
  if [ "$KC_AUTH" = client ]; then who="client $KC_CLIENT_ID (secret from KC_CLIENT_SECRET or a silent prompt)"; else who="$KC_ADMIN_USER (password prompted by kcadm)"; fi
  echo "login to master as $who; delete user $BOOT_USER from realm master; rm $BOOT_FILE; $COMPOSE up -d --no-deps --force-recreate keycloak"
  exit 0
fi
kc_login
id="$($COMPOSE exec -T keycloak $K get users -r master -q "username=$BOOT_USER" --fields id --format csv --noquotes | tr -d '\r' | head -1)"
if [ -n "$id" ]; then $COMPOSE exec -T keycloak $K delete "users/$id" -r master; echo "deleted $BOOT_USER"; else echo "$BOOT_USER not present"; fi
kc_logout; trap - EXIT
rm -f "$BOOT_FILE"
$COMPOSE up -d --no-deps --force-recreate keycloak
echo "bootstrap admin retired; keycloak re-created without it"
