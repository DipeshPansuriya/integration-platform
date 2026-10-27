#!/bin/sh
# Sourced by retire-bootstrap-admin.sh, rotate-secret.sh, seed/seed-showcase.sh and ops-setup.sh (row 543). Defines how the HOST
# scripts open a kcadm session inside the keycloak container, so no script needs the master admin's password (that admin has TOTP,
# and kcadm's password grant cannot send a one-time code).
#   default            master-realm service-account client (client credentials). Client id: KC_CLIENT_ID (default ipass-kcadm).
#                      Secret: KC_CLIENT_SECRET from the environment (export it from your password manager / OS store for one
#                      command) or, on a terminal, a silent prompt. Never read from a file, never printed, never in argv: it
#                      travels as the environment NAME KC_CLIENT_SECRET (docker compose exec -e).
#   --password-login   explicit FALLBACK (or KC_AUTH=password): the named admin's password, prompted by kcadm. Only works while
#                      that admin has NO second factor. Any other KC_AUTH value is refused.
# Created by keycloak/kcadm-client-setup.sh. Functions: kc_parse_flags, kc_login, kc_logout, kc_require_login_inputs.
KC_CLIENT_ID="${KC_CLIENT_ID:-ipass-kcadm}"
KC_AUTH="${KC_AUTH:-client}"
KC_FLAGS_REST=""

# kc_parse_flags "$@" : consumes a leading --password-login (sets KC_AUTH=password); the rest is left in "$@" by the caller via
# `shift $KC_SHIFT` (POSIX sh has no way to modify the caller's positional parameters).
kc_parse_flags() { KC_SHIFT=0; case "${1:-}" in --password-login) KC_AUTH=password; KC_SHIFT=1;; esac; }

kc_auth_check() {
  case "$KC_AUTH" in client|password) return 0;; *) echo "KC_AUTH must be client or password (got '$KC_AUTH')" >&2; return 2;; esac
}

# kc_read_secret : fills KC_CLIENT_SECRET (silent prompt on a terminal), exports it. Nothing is echoed.
kc_read_secret() {
  if [ -z "${KC_CLIENT_SECRET:-}" ]; then
    if [ -t 0 ] && [ -z "${KC_NO_PROMPT:-}" ]; then
      printf 'secret of client %s (not echoed): ' "$KC_CLIENT_ID" >&2
      _kc_old="$(stty -g 2>/dev/null || true)"; stty -echo 2>/dev/null || true
      read -r KC_CLIENT_SECRET || true
      [ -z "$_kc_old" ] || stty "$_kc_old" 2>/dev/null || true
      echo >&2
    fi
  fi
  [ -n "${KC_CLIENT_SECRET:-}" ] || { echo "no client secret: export KC_CLIENT_SECRET (from your password manager) or run on a terminal to be prompted; or use --password-login while the admin has no TOTP" >&2; return 2; }
  export KC_CLIENT_SECRET
}

# kc_require_login_inputs : call BEFORE anything is changed. Client mode: make sure the secret is there. Password mode: nothing to check
# here (kcadm prompts for the password).
kc_require_login_inputs() { kc_auth_check || return $?; [ "$KC_AUTH" = client ] && { kc_read_secret || return $?; }; return 0; }

# kc_login : needs $COMPOSE, optional $KCADM. Opens the kcadm session in the container; arms kc_logout on exit.
kc_login() {
  _K="${KCADM:-/opt/keycloak/bin/kcadm.sh}"; _SRV=http://localhost:8080
  kc_require_login_inputs || return $?
  if [ "$KC_AUTH" = client ]; then
    $COMPOSE exec -T -e KC_CLIENT_SECRET keycloak sh -c "$_K config credentials --server $_SRV --realm master --client '$KC_CLIENT_ID' --secret \"\$KC_CLIENT_SECRET\"" >/dev/null
  else
    : "${KC_ADMIN_USER:?--password-login needs KC_ADMIN_USER (a named realm admin without TOTP)}"
    $COMPOSE exec keycloak $_K config credentials --server $_SRV --realm master --user "$KC_ADMIN_USER" >/dev/null
  fi
  trap 'kc_logout' EXIT
}

# kc_logout : removes the token (and, in client mode, the stored client secret) kcadm keeps in the container's writable layer.
kc_logout() { ${COMPOSE:-docker compose} exec -T keycloak "${KCADM:-/opt/keycloak/bin/kcadm.sh}" config truncate >/dev/null 2>&1 || true; }
