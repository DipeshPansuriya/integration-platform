#!/bin/sh
# Secrets and the login role of the outside door. Run from the project folder on the server (/docker/ipass).
#   ./outside/outside-role.sh env       make sure .env has DEMO_ADMIN_PASSWORD and OUTSIDE_DB_PASSWORD (generated HERE, mode 600);
#                                       only 8-character fingerprints are printed. Needed BEFORE compose up.
#   ./outside/outside-role.sh up        create / refresh ipass_outside inside pg-demo with the .env password (outside-role.sql)
#   ./outside/outside-role.sh rotate    new OUTSIDE_DB_PASSWORD: applied to pg-demo first, then written to .env (old one kept on failure)
#   ./outside/outside-role.sh status    role attributes (no secrets)
# The password never goes on a command line, into a log, or into script output: it travels as an environment variable
# (`exec -e OUT_PW`, no value on the command line) and psql reads it with \getenv; pg-demo logs no statement text.
# Test hooks: DEMO_EXEC (default: docker compose --env-file <env> -f outside/compose.outside.yaml exec -T -e OUT_PW pg-demo),
# ENV_FILE (default ./.env), ROTATION_LOG.
set -eu
umask 077
HERE="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${ENV_FILE:-./.env}"
DEMO_EXEC="${DEMO_EXEC:-docker compose --env-file $ENV_FILE -f $HERE/compose.outside.yaml exec -T -e OUT_PW pg-demo}"
export MSYS_NO_PATHCONV=1
rand() { openssl rand -base64 96 | tr -dc 'A-Za-z0-9' | cut -c1-48; }
fp() { printf '%s' "$1" | openssl dgst -sha256 | sed 's/^.*= *//' | cut -c1-8; }
getv() { sed -n "s/^$1=//p" "$ENV_FILE" | head -1; }
setv() { # name value -> .env, atomic, mode 600
  t="$(mktemp "$ENV_FILE.XXXXXX")"
  { grep -v "^$1=" "$ENV_FILE" || true; echo "$1=$2"; } > "$t" && chmod 600 "$t" && mv "$t" "$ENV_FILE"
}
log() { echo "$(date -u +%FT%TZ) $1 fingerprint $2" | tee -a "${ROTATION_LOG:-./.rotation-log}" >/dev/null; echo "$1 fingerprint $2"; }
apply() { # password -> pg-demo
  # shellcheck disable=SC2086
  OUT_PW="$1" $DEMO_EXEC sh -c 'psql -X -v ON_ERROR_STOP=1 -q -U ipass_demo_admin -d ipass_demo -v db=ipass_demo -f -' < "$HERE/outside-role.sql"
}
cmd="${1:?usage: outside-role.sh env | up | rotate | status}"
[ -f "$ENV_FILE" ] || { echo "no $ENV_FILE here" >&2; exit 1; }
case "$cmd" in
  env)
    for v in DEMO_ADMIN_PASSWORD OUTSIDE_DB_PASSWORD; do
      if [ -n "$(getv $v)" ]; then echo "$v already set"; else p="$(rand)"; setv $v "$p"; log $v "$(fp "$p")"; fi
    done
    ;;
  up)
    pw="$(getv OUTSIDE_DB_PASSWORD)"; [ "${#pw}" -ge 32 ] || { echo "OUTSIDE_DB_PASSWORD is missing or shorter than 32 characters (run: outside-role.sh env)" >&2; exit 2; }
    apply "$pw"
    echo "ipass_outside is ready in pg-demo. The password is in $ENV_FILE (OUTSIDE_DB_PASSWORD); it was not printed."
    ;;
  rotate)
    pw="$(rand)"; apply "$pw"; setv OUTSIDE_DB_PASSWORD "$pw"; log OUTSIDE_DB_PASSWORD "$(fp "$pw")"
    echo "rotated; existing outside sessions keep running until they end - new logins need the new password (hand it over as in OUTSIDE-ACCESS.md section 5)"
    ;;
  status)
    # shellcheck disable=SC2086
    $DEMO_EXEC sh -c 'psql -X -U ipass_demo_admin -d ipass_demo -c "SELECT rolname, rolcanlogin, rolsuper, rolbypassrls, rolconnlimit, rolconfig FROM pg_roles WHERE rolname = '"'"'ipass_outside'"'"'"'
    ;;
  *) echo "usage: outside-role.sh env | up | rotate | status" >&2; exit 2;;
esac
