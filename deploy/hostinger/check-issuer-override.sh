#!/bin/sh
# Preflight for the environment file: compose.yaml defaults KEYCLOAK_ADMIN_BASE_URI to the bundled Keycloak
# (http://keycloak:8080). If OIDC_ISSUER_URI is overridden to an external identity provider while
# KEYCLOAK_ADMIN_BASE_URI is left unset, the app's admin calls (client create, lookup, disable) would still go to the
# bundled Keycloak, with the other provider's realm. This script fails in that case. It prints variable NAMES only.
# Usage:  ./check-issuer-override.sh [env-file]      (default ./.env)   Run it before `docker compose up`.
ENV_FILE="${1:-./.env}"
[ -r "$ENV_FILE" ] || { echo "check-issuer-override: cannot read the env file" >&2; exit 2; }
value() { grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2-; }
issuer="$(value OIDC_ISSUER_URI)"
admin="$(value KEYCLOAK_ADMIN_BASE_URI)"
if [ -n "$issuer" ] && [ -z "$admin" ]; then
  echo "FAIL: OIDC_ISSUER_URI is overridden but KEYCLOAK_ADMIN_BASE_URI is not set in the env file." >&2
  echo "      Without it the admin calls go to the bundled Keycloak (http://keycloak:8080) with the wrong realm." >&2
  echo "      Set KEYCLOAK_ADMIN_BASE_URI to that identity provider's internal admin address (README, Keycloak admin address)." >&2
  exit 1
fi
echo "OK: no issuer override without an explicit KEYCLOAK_ADMIN_BASE_URI"
