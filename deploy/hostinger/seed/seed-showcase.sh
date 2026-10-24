#!/bin/sh
# Showcase seed, run on the server in the project directory (/docker/ipass) after the stack is healthy:
#   ./seed/seed-showcase.sh          (DRY_RUN=1 prints the plan only)
# 1. creates four fictional realm users through Keycloak's admin CLI INSIDE the container (admin is never routed),
#    passwords from SHOWCASE_PASSWORD_* in the server .env - never printed;
# 2. binds them to two tenants with seed.sql (tenant, app_user, role_grant), as the bootstrap superuser.
# Idempotent. Needs: docker compose, the server .env, the stack up.
set -eu
cd "$(dirname "$0")/.."
ENV_FILE="${ENV_FILE:-./.env}"
COMPOSE="${COMPOSE:-docker compose}"
if [ "${DRY_RUN:-0}" = 1 ]; then
  echo "would read $ENV_FILE, create realm users showcase-admin-a showcase-configurator-a showcase-approver-a showcase-admin-b in realm platform,"
  echo "then run seed/seed.sql (tenants showcase-a, showcase-b) through: $COMPOSE exec -T postgres psql"
  exit 0
fi
set -a; . "$ENV_FILE"; set +a
ISSUER="${OIDC_ISSUER_URI:-https://auth.${IPASS_DOMAIN}/realms/platform}"
KCADM=/opt/keycloak/bin/kcadm.sh

# passwords travel as environment NAMES (docker compose exec -e NAME) so no value reaches a host command line
$COMPOSE exec -T keycloak sh -c "$KCADM config credentials --server http://localhost:8080 --realm master --user \"\$KC_BOOTSTRAP_ADMIN_USERNAME\" --password \"\$KC_BOOTSTRAP_ADMIN_PASSWORD\"" >/dev/null

ensure_user() { # username, password-variable-name -> prints the realm user id
  user="$1"; pwvar="$2"
  id="$($COMPOSE exec -T keycloak $KCADM get users -r platform -q "username=$user" --fields id --format csv --noquotes | tr -d '\r' | head -1)"
  if [ -z "$id" ]; then
    id="$($COMPOSE exec -T keycloak $KCADM create users -r platform -s "username=$user" -s enabled=true -s "email=$user@showcase.invalid" -s emailVerified=true -i | tr -d '\r')"
  fi
  $COMPOSE exec -T -e "$pwvar" keycloak sh -c "$KCADM set-password -r platform --username $user --new-password \"\$$pwvar\"" >/dev/null
  echo "$id"
}
export SHOWCASE_PASSWORD_ADMIN_A SHOWCASE_PASSWORD_CONFIGURATOR_A SHOWCASE_PASSWORD_APPROVER_A SHOWCASE_PASSWORD_ADMIN_B
sub_admin_a="$(ensure_user showcase-admin-a SHOWCASE_PASSWORD_ADMIN_A)"
sub_cfg_a="$(ensure_user showcase-configurator-a SHOWCASE_PASSWORD_CONFIGURATOR_A)"
sub_appr_a="$(ensure_user showcase-approver-a SHOWCASE_PASSWORD_APPROVER_A)"
sub_admin_b="$(ensure_user showcase-admin-b SHOWCASE_PASSWORD_ADMIN_B)"

$COMPOSE exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v issuer="'"$ISSUER"'" -v sub_admin_a="'"$sub_admin_a"'" -v sub_cfg_a="'"$sub_cfg_a"'" -v sub_appr_a="'"$sub_appr_a"'" -v sub_admin_b="'"$sub_admin_b"'" -f -' < seed/seed.sql
echo "seeded: tenants showcase-a, showcase-b; users showcase-admin-a, showcase-configurator-a, showcase-approver-a, showcase-admin-b"
