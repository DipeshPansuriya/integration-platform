#!/bin/bash
# Row 543 - creates (or re-checks, or rotates) the master-realm service-account client the repo scripts log in with (ops-setup,
# rotate-secret, seed-showcase, retire-bootstrap-admin), so none of them needs the named master admin's password once that admin has
# TOTP (kcadm's password grant cannot send a one-time code). Run on the server in the project directory, stack up:
#   ./keycloak/kcadm-client-setup.sh [--dry-run] [--rotate] [--with-master-users]
#     --dry-run            print the plan, call nothing
#     --rotate             set a NEW secret on the existing client (then prove it by a login)
#     --with-master-users  also grant master-realm manage-users/view-users (needed ONLY by retire-bootstrap-admin.sh); re-run
#                          without the flag afterwards to take them away again
# ORDER (the whole chain needs the admin password, so finish it BEFORE the named admin enrols TOTP; afterwards use the console):
#   1. create:  --with-master-users     2. keycloak/retire-bootstrap-admin.sh     3. drop the master roles: run WITHOUT --with-master-users
#   4. rotate the secret: --rotate (the old secret held master power)     5. only now enrol TOTP on the named admin
# It logs in ONCE as the named master admin (password), also for steps 3 and 4. Or create the client in the console
# (see the row 543 runbook). Idempotent: an existing client keeps its secret and its roles are reconciled to the exact set in
# keycloak/kcadm-client-setup-kcadm.sh. Secrets never touch a file: the admin password and the client secret come from the
# environment (KC_ADMIN_USERNAME, KC_ADMIN_PASSWORD, KC_NEW_CLIENT_SECRET - at least 32 characters, from your password manager; never typed inline, the shell history keeps it) or a
# silent prompt, travel into the container as environment NAMES, and are never printed. Store the client secret in the password manager.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
COMPOSE="${COMPOSE:-docker compose}"
ROTATE=0; WITH_MASTER_USERS=0; DRY_RUN="${DRY_RUN:-0}"
for a in "$@"; do case "$a" in
  --dry-run) DRY_RUN=1;; --rotate) ROTATE=1;; --with-master-users) WITH_MASTER_USERS=1;;
  *) echo "usage: kcadm-client-setup.sh [--dry-run] [--rotate] [--with-master-users]" >&2; exit 2;; esac; done
KC_CLIENT_ID="${KC_CLIENT_ID:-ipass-kcadm}"
case "$KC_CLIENT_ID" in ""|*[!A-Za-z0-9._-]*) echo "KC_CLIENT_ID may contain only letters, digits, . _ -" >&2; exit 2;; esac
if [ "$DRY_RUN" = 1 ]; then
  echo "would log in once to realm master as the named admin (KC_ADMIN_USERNAME/KC_ADMIN_PASSWORD from the environment, else prompted silently),"
  echo "ensure client $KC_CLIENT_ID (confidential, service account, client credentials only; created with KC_NEW_CLIENT_SECRET or a silent prompt, rotated only with --rotate),"
  echo "reconcile its service-account roles to platform-realm: manage-clients manage-realm manage-users view-clients view-realm view-users"
  [ "$WITH_MASTER_USERS" = 1 ] && echo "plus master-realm: manage-users view-users (retire-bootstrap-admin)" || echo "and NO master-realm role (add them only with --with-master-users)"
  echo "then prove a new secret by a client-credentials login and a call into realm platform, and truncate the kcadm token"
  exit 0
fi
prompt() { # NAME LABEL silent
  local n="$1"; [ -n "${!n:-}" ] && return 0
  [ -t 0 ] || { echo "$n is not set and there is no terminal to ask on" >&2; return 2; }
  if [ "$3" = silent ]; then read -r -s -p "$2 (not echoed): " "$n"; echo; else read -r -p "$2: " "$n"; fi
}
prompt KC_ADMIN_USERNAME "named master admin user" plain
prompt KC_ADMIN_PASSWORD "password of that admin" silent
# the container script decides whether a secret is needed (new client or --rotate); a re-run on an existing client needs none
if [ -z "${KC_NEW_CLIENT_SECRET:-}" ] && [ -t 0 ]; then
  printf 'new client secret, at least 32 characters - REQUIRED for the first run; leave empty only if %s already exists and you are not rotating (not echoed): ' "$KC_CLIENT_ID" >&2
  read -r -s KC_NEW_CLIENT_SECRET || true; echo >&2
  if [ -n "${KC_NEW_CLIENT_SECRET:-}" ]; then
    read -r -s -p "repeat it (not echoed): " again; echo >&2
    [ "$again" = "$KC_NEW_CLIENT_SECRET" ] || { echo "the two entries differ" >&2; exit 2; }
  fi
fi
export KC_ADMIN_USERNAME KC_ADMIN_PASSWORD KC_CLIENT_ID ROTATE WITH_MASTER_USERS
export KC_NEW_CLIENT_SECRET="${KC_NEW_CLIENT_SECRET:-}"
$COMPOSE exec -T -e KC_ADMIN_USERNAME -e KC_ADMIN_PASSWORD -e KC_CLIENT_ID -e KC_NEW_CLIENT_SECRET -e ROTATE -e WITH_MASTER_USERS keycloak bash -s < "$HERE/kcadm-client-setup-kcadm.sh"
echo "store the client secret of $KC_CLIENT_ID in your password manager; the repo scripts read it from KC_CLIENT_SECRET or a silent prompt"
