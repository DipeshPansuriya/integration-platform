#!/bin/bash
# T219 - creates, in the Keycloak realm `platform`, what Kafbat on the operator host (ops.<domain>) needs. Run on the
# server in the project directory, with the stack up. On an EXISTING install run this BEFORE any other `docker compose`
# command: compose.yaml now requires OPS_KAFBAT_CLIENT_SECRET, which this script generates in .env when it is missing.
#   ./keycloak/ops-setup.sh        (DRY_RUN=1 prints the plan only)
#   1. realm role  ops-operator            (no user is created or assigned: that is the owner's step)
#   2. client      ops-kafbat              confidential, authorization-code only, full scope OFF, only that role mapped,
#                                          `roles` claim, exact redirect URI https://ops.<domain>/kafka/login/oauth2/code/keycloak
#   3. flow        ops-browser             username+password -> deny anyone WITHOUT ops-operator -> OTP (required; a user with
#                                          no authenticator is made to enrol one). No SSO-cookie shortcut. Bound to ops-kafbat
#                                          ONLY (platform logins unchanged), and only after it verified; a re-run re-verifies it.
# The kcadm logic is keycloak/ops-setup-kcadm.sh (run inside the container; stub-tested by test/ops-setup-stub.sh).
# Login (row 543): the master service-account client ipass-kcadm (keycloak/kcadm-client-setup.sh). Its secret: KC_CLIENT_SECRET from
# the environment, else a silent prompt; never read from a file, never printed. The named admin has TOTP and kcadm cannot send a
# one-time code, so the admin-password login is only the explicit fallback:  ./keycloak/ops-setup.sh --password-login
# (named master admin: KC_ADMIN_USERNAME + KC_ADMIN_PASSWORD from the environment, else from ./.env.kc-admin (mode 600, created once with:
# umask 077; printf 'KC_ADMIN_USERNAME=%s\nKC_ADMIN_PASSWORD=%s\n' "$name" "$pw" > .env.kc-admin ), else you are asked (silently)).
# Values travel as environment NAMES and are never printed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
. "$HERE/kc-login.sh"
kc_parse_flags "$@"; shift "$KC_SHIFT"
kc_auth_check
ENV_FILE="${ENV_FILE:-./.env}"
ADMIN_FILE="${KC_ADMIN_FILE:-./.env.kc-admin}"
COMPOSE="${COMPOSE:-docker compose}"
if [ "${DRY_RUN:-0}" = 1 ]; then
  if [ "$KC_AUTH" = client ]; then who="log in as the master client $KC_CLIENT_ID (secret from KC_CLIENT_SECRET or a silent prompt)"
  else who="log in as the named master admin (password fallback: KC_ADMIN_USERNAME/KC_ADMIN_PASSWORD from the environment or $ADMIN_FILE, else prompted)"; fi
  echo "would read $ENV_FILE (names only), generate OPS_KAFBAT_CLIENT_SECRET if missing, $who,"
  echo "then in realm platform: ensure role ops-operator, client ops-kafbat, mapper roles, flow ops-browser (password, role gate, OTP) verified, bind the flow to the client"
  exit 0
fi
umask 077
[ -s "$ENV_FILE" ] || { echo "$ENV_FILE is missing or empty - run in the project directory" >&2; exit 2; }
if ! grep -q '^OPS_KAFBAT_CLIENT_SECRET=.' "$ENV_FILE"; then
  v="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-40)"
  [ "${#v}" -ge 32 ] || { echo "could not generate a secret (openssl)" >&2; exit 2; }
  [ -z "$(tail -c1 "$ENV_FILE")" ] || echo >> "$ENV_FILE"
  printf 'OPS_KAFBAT_CLIENT_SECRET=%s\n' "$v" >> "$ENV_FILE"
  echo "OPS_KAFBAT_CLIENT_SECRET generated, fingerprint $(printf '%s' "$v" | openssl dgst -sha256 | sed 's/^.*= *//' | cut -c1-8)"
fi
set -a; . "$ENV_FILE"; set +a
if [ "$KC_AUTH" = client ]; then
  kc_read_secret                                    # exports KC_CLIENT_SECRET (env or silent prompt)
  LOGIN_ENV=(-e KC_AUTH -e KC_CLIENT_ID -e KC_CLIENT_SECRET)
else
  if [ -z "${KC_ADMIN_USERNAME:-}" ] || [ -z "${KC_ADMIN_PASSWORD:-}" ]; then
    if [ -s "$ADMIN_FILE" ]; then set -a; . "$ADMIN_FILE"; set +a
    else
      read -r -p "named master admin user: " KC_ADMIN_USERNAME
      read -r -s -p "password (not echoed): " KC_ADMIN_PASSWORD; echo
    fi
  fi
  : "${KC_ADMIN_USERNAME:?}" "${KC_ADMIN_PASSWORD:?}"; export KC_ADMIN_USERNAME KC_ADMIN_PASSWORD
  LOGIN_ENV=(-e KC_AUTH -e KC_ADMIN_USERNAME -e KC_ADMIN_PASSWORD)
fi
: "${IPASS_DOMAIN:?}" "${OPS_KAFBAT_CLIENT_SECRET:?}"
export IPASS_DOMAIN KC_AUTH KC_CLIENT_ID OPS_KAFBAT_CLIENT_SECRET
$COMPOSE exec -T -e IPASS_DOMAIN -e OPS_KAFBAT_CLIENT_SECRET "${LOGIN_ENV[@]}" keycloak bash -s < "$HERE/ops-setup-kcadm.sh"
echo "ops-setup done (no user created: assign role ops-operator to the named operator accounts)"
