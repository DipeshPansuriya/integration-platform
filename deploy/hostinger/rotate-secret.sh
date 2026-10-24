#!/bin/sh
# Rotates ONE secret at a time, in the project folder on the server (the only supported rotation; bootstrap-env.sh never
# overwrites a .env). The new value is generated here, applied at its source first, written to .env only after that
# worked, and never printed (8-character SHA-256 fingerprint only). Each rotation is logged without the value to .rotation-log.
#   ./rotate-secret.sh DB_PASSWORD | DB_MIGRATION_PASSWORD | BACKUP_DB_PASSWORD | RESTORE_DB_PASSWORD
#                      | KEYCLOAK_DB_PASSWORD | POSTGRES_PASSWORD
#       -> ALTER ROLE inside the postgres container, .env updated, the dependent service re-created.
#   KC_ADMIN_USER=<named admin> ./rotate-secret.sh CONSOLE_OIDC_CLIENT_SECRET | PLATFORM_ADMIN_CLIENT_SECRET | OPERATOR_AGENT_CLIENT_SECRET
#       -> kcadm sets the realm client secret (the admin password is prompted by kcadm, never passed here), .env updated.
# Not rotated here, on purpose: PLATFORM_CREDENTIAL_KEK_* (a new key means re-sealing every stored target credential;
# see README "Rotation") and SHOWCASE_PASSWORD_* (edit .env, re-run ./seed/seed-showcase.sh).
# DRY_RUN=1 prints the plan. ROTATE_SOURCE_ONLY=1 defines the functions only (tests).
set -eu
umask 077
ENV_FILE="${ENV_FILE:-./.env}"
COMPOSE="${COMPOSE:-docker compose}"
LOG="${ROTATION_LOG:-./.rotation-log}"

rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-40; }
fp() { printf '%s' "$1" | openssl dgst -sha256 | sed 's/^.*= *//' | cut -c1-8; }

# set_env_value FILE NAME VALUE: replace one line atomically; the value travels in the environment, not on a command line
set_env_value() {
  f="$1"; n="$2"
  grep -q "^$n=" "$f" || { echo "$n is not in $f" >&2; return 1; }
  t="$(mktemp "$f.XXXXXX")"
  NEWVAL="$3" awk -v n="$n" 'BEGIN{FS=OFS="="} $1==n{print n "=" ENVIRON["NEWVAL"]; next} {print}' "$f" > "$t" && chmod 600 "$t" && mv "$t" "$f" || { rm -f "$t"; return 1; }
}

# plan NAME -> sets KIND (db|client), TARGET (role or clientId), SERVICES (re-created afterwards)
plan() {
  case "$1" in
    DB_PASSWORD)            KIND=db; TARGET=ipass_app; SERVICES=platform-app;;
    DB_MIGRATION_PASSWORD)  KIND=db; TARGET=ipass_migrator; SERVICES=platform-app;;
    BACKUP_DB_PASSWORD)     KIND=db; TARGET=ipass_backup; SERVICES=pg-backup;;
    RESTORE_DB_PASSWORD)    KIND=db; TARGET=ipass_restore; SERVICES="";;
    KEYCLOAK_DB_PASSWORD)   KIND=db; TARGET="${KEYCLOAK_DB_USERNAME:-keycloak}"; SERVICES="keycloak pg-backup";;
    POSTGRES_PASSWORD)      KIND=db; TARGET="${POSTGRES_USER:-ipass_admin}"; SERVICES="";;
    CONSOLE_OIDC_CLIENT_SECRET)  KIND=client; TARGET=platform-console; SERVICES=platform-app;;
    PLATFORM_ADMIN_CLIENT_SECRET) KIND=client; TARGET=platform-admin; SERVICES=platform-app;;
    OPERATOR_AGENT_CLIENT_SECRET) KIND=client; TARGET=operator-agent; SERVICES="";;
    PLATFORM_CREDENTIAL_KEK_*|PLATFORM_CREDENTIAL_KEK)
      echo "refused: a new KEK must keep the old one until every sealed credential is re-sealed - see README 'Rotation'" >&2; return 2;;
    SHOWCASE_PASSWORD_*)
      echo "refused: edit $1 in $ENV_FILE and re-run ./seed/seed-showcase.sh" >&2; return 2;;
    *) echo "refused: $1 is not a rotatable secret of this stack" >&2; return 2;;
  esac
}

apply_db() { # role, value
  printf "SELECT format('ALTER ROLE %%I PASSWORD %%L', :'role', :'pw') \\\\gexec\n" |
    NEWPW="$2" $COMPOSE exec -T -e NEWPW postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v role='"$1"' -v pw="$NEWPW"'
}

apply_client() { # clientId, value (the admin password is prompted by kcadm on a terminal)
  : "${KC_ADMIN_USER:?set KC_ADMIN_USER to a named realm admin (the bootstrap admin is retired)}"
  K=/opt/keycloak/bin/kcadm.sh
  $COMPOSE exec keycloak $K config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN_USER"
  id="$($COMPOSE exec -T keycloak $K get clients -r platform -q "clientId=$1" --fields id --format csv --noquotes | tr -d '\r' | head -1)"
  [ -n "$id" ] || { echo "client $1 not found in realm platform" >&2; return 1; }
  NEWPW="$2" $COMPOSE exec -T -e NEWPW keycloak sh -c "$K update clients/$id -r platform -s \"secret=\$NEWPW\""
}

if [ "${ROTATE_SOURCE_ONLY:-0}" != 1 ]; then
  name="${1:?usage: rotate-secret.sh NAME (see the header of this file)}"
  plan "$name" || exit 2
  if [ "${DRY_RUN:-0}" = 1 ]; then
    echo "would generate a new $name, apply it to $KIND $TARGET, write it to $ENV_FILE, re-create: ${SERVICES:-nothing}, log a fingerprint to $LOG"
    exit 0
  fi
  [ -f "$ENV_FILE" ] || { echo "no $ENV_FILE here" >&2; exit 1; }
  set -a; . "$ENV_FILE"; set +a
  v="$(rand)"
  if [ "$KIND" = db ]; then apply_db "$TARGET" "$v"; else apply_client "$TARGET" "$v"; fi
  set_env_value "$ENV_FILE" "$name" "$v"
  echo "$(date -u +%FT%TZ) $name fingerprint $(fp "$v")" | tee -a "$LOG"
  # shellcheck disable=SC2086
  [ -z "$SERVICES" ] || $COMPOSE up -d --no-deps --force-recreate $SERVICES
  echo "rotated $name. A later plain 'up -d' may also re-create postgres once (its environment holds the role passwords)."
fi
