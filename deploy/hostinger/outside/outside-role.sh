#!/bin/sh
# Creates / removes the outside login role (outside-role.sql / outside-role-drop.sql) from the project folder on the server.
#   ./outside/outside-role.sh up <tenant_code>   create or refresh the role pinned to that tenant; on the first run it
#                                                generates the password HERE (48 random characters), writes it to .env as
#                                                OUTSIDE_DB_PASSWORD (mode 600) and prints an 8-character fingerprint only
#   ./outside/outside-role.sh down               drop the role, its policies and settings, remove the .env line
#   ./outside/outside-role.sh status             role attributes (no secrets)
# The password never goes on a command line: it reaches psql through the environment (\getenv). Test hooks:
# PSQL_EXEC (default "docker compose exec -T -e OUT_PW postgres"), ENV_FILE (default ./.env).
set -eu
umask 077
HERE="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${ENV_FILE:-./.env}"
PSQL_EXEC="${PSQL_EXEC:-docker compose exec -T -e OUT_PW postgres}"
export MSYS_NO_PATHCONV=1
rand() { openssl rand -base64 96 | tr -dc 'A-Za-z0-9' | cut -c1-48; }
fp() { printf '%s' "$1" | openssl dgst -sha256 | sed 's/^.*= *//' | cut -c1-8; }
psql_in() { # sql-file, [tail args for psql]
  f="$1"; shift
  # shellcheck disable=SC2086
  $PSQL_EXEC sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v db="$POSTGRES_DB" "$@" -f -' sh "$@" < "$f"
}
cmd="${1:?usage: outside-role.sh up <tenant_code> | down | status}"
[ -f "$ENV_FILE" ] || { echo "no $ENV_FILE here" >&2; exit 1; }
case "$cmd" in
  up)
    tenant="${2:?usage: outside-role.sh up <tenant_code>}"
    case "$tenant" in *[!A-Za-z0-9._-]*|"") echo "tenant code has a character it should not" >&2; exit 2;; esac
    if grep -q '^OUTSIDE_DB_PASSWORD=.' "$ENV_FILE"; then
      pw="$(sed -n 's/^OUTSIDE_DB_PASSWORD=//p' "$ENV_FILE" | head -1)"; new=0
    else
      pw="$(rand)"; new=1
    fi
    [ "${#pw}" -ge 32 ] || { echo "OUTSIDE_DB_PASSWORD is shorter than 32 characters" >&2; exit 2; }
    export OUT_PW="$pw"
    psql_in "$HERE/outside-role.sql" -v "tenant_code=$tenant"
    if [ "$new" = 1 ]; then
      t="$(mktemp "$ENV_FILE.XXXXXX")"; { grep -v '^OUTSIDE_DB_PASSWORD=' "$ENV_FILE" || true; echo "OUTSIDE_DB_PASSWORD=$pw"; } > "$t" && chmod 600 "$t" && mv "$t" "$ENV_FILE"
    fi
    echo "$(date -u +%FT%TZ) OUTSIDE_DB_PASSWORD fingerprint $(fp "$pw")" | tee -a "${ROTATION_LOG:-./.rotation-log}"
    echo "ipass_outside is ready for tenant $tenant. The password is in $ENV_FILE (OUTSIDE_DB_PASSWORD); it was not printed."
    ;;
  down)
    psql_in "$HERE/outside-role-drop.sql"
    t="$(mktemp "$ENV_FILE.XXXXXX")"; { grep -v '^OUTSIDE_DB_PASSWORD=' "$ENV_FILE" || true; } > "$t" && chmod 600 "$t" && mv "$t" "$ENV_FILE"
    echo "ipass_outside removed; OUTSIDE_DB_PASSWORD removed from $ENV_FILE"
    ;;
  status)
    # shellcheck disable=SC2086
    $PSQL_EXEC sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT rolname, rolcanlogin, rolsuper, rolbypassrls, rolconnlimit, rolconfig FROM pg_roles WHERE rolname = '"'"'ipass_outside'"'"'"'
    ;;
  *) echo "usage: outside-role.sh up <tenant_code> | down | status" >&2; exit 2;;
esac
