#!/bin/sh
# Retires the temporary Keycloak bootstrap admin (README step 11), in the project folder on the server:
#   1. create a named admin first:  docker compose exec keycloak /opt/keycloak/bin/kcadm.sh ...   (master realm, admin role)
#   2. KC_ADMIN_USER=<that named admin> ./keycloak/retire-bootstrap-admin.sh     (kcadm prompts for its password)
# It logs in as the NAMED admin, deletes the user bootstrap-admin from the master realm, deletes the credential file
# .env.bootstrap-admin, and re-creates keycloak from compose.yaml alone (no bootstrap variables left in the container).
# DRY_RUN=1 prints the steps.
set -eu
cd "$(dirname "$0")/.."
COMPOSE="${COMPOSE:-docker compose}"
BOOT_USER=bootstrap-admin
BOOT_FILE="${IPASS_BOOTSTRAP_ADMIN_FILE:-.env.bootstrap-admin}"
K=/opt/keycloak/bin/kcadm.sh
: "${KC_ADMIN_USER:?set KC_ADMIN_USER to the named admin that replaces $BOOT_USER}"
[ "$KC_ADMIN_USER" != "$BOOT_USER" ] || { echo "KC_ADMIN_USER must be a different user than $BOOT_USER" >&2; exit 2; }
if [ "${DRY_RUN:-0}" = 1 ]; then
  echo "login to master as $KC_ADMIN_USER (prompted); delete user $BOOT_USER from realm master; rm $BOOT_FILE; $COMPOSE up -d --no-deps --force-recreate keycloak"
  exit 0
fi
$COMPOSE exec keycloak $K config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN_USER"
id="$($COMPOSE exec -T keycloak $K get users -r master -q "username=$BOOT_USER" --fields id --format csv --noquotes | tr -d '\r' | head -1)"
if [ -n "$id" ]; then $COMPOSE exec -T keycloak $K delete "users/$id" -r master; echo "deleted $BOOT_USER"; else echo "$BOOT_USER not present"; fi
rm -f "$BOOT_FILE"
$COMPOSE up -d --no-deps --force-recreate keycloak
echo "bootstrap admin retired; keycloak re-created without it"
