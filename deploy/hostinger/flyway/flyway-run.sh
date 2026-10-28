#!/bin/sh
# The ONE place that starts the Flyway container (sourced by flyway/migrate.sh, which deploy.sh calls, and by test/flyway-local-proof.sh).
#   FW_IMAGE FW_NETWORK FW_URL FW_DB_USER FW_DB_PASSWORD   required; DOCKER = the docker binary (default docker)
#   TEST ONLY (refused unless IPASS_FLYWAY_TEST=1): FW_DOCKER_ARGS, FW_RUN_USER (never uid 0), FW_SECRET_BASE
#   FW_DOCKER_ARGS  optional extra `docker run` arguments (tests only, e.g. --name x -v dir:/probe:ro), split on blanks
#   FW_RUN_USER     optional uid:gid for the container (default: the calling user; a root caller gets 61001:61001, a uid in no passwd file)
# Secret handling: the password is written to a mode-600 TOML file in a mode-700 temp directory (mktemp, under $TMPDIR or /tmp),
# mounted read-only at /run/flyway-secret and handed to Flyway with -configFiles. It is NEVER an environment variable (those show
# in `docker inspect` Config.Env), never a command-line argument (those show in the host process list). The directory is removed
# as soon as the container exits, and by flyway_cleanup (callers trap EXIT/INT/TERM with it) when the script is interrupted.
# Container hardening: read-only root file system with a small tmpfs /tmp (Flyway needs HOME and temp space only), all
# capabilities dropped, no-new-privileges, non-root user. A normal caller runs the container as its own uid (so its 600 file is readable;
# TMPDIR or /tmp holds the directory). A ROOT caller (deploy.sh on the VPS) keeps the directory under the root-owned /run/ipass-flyway (700),
# chowns just the directory and the one file to 61001:61001 (no -R; a uid that is in no passwd file, so no host account can read or
# replace the password file) and runs the container as that uid.
# The calling script has already refused a password with a line break; backslash and double quote are escaped for the TOML basic string.
# flyway_verify_image: before the password is handed over, the image must carry the expected commit. FW_EXPECT_REVISION = the release
# tag (the start of the full sha in the label org.opencontainers.image.revision, set by build-image.sh); FW_EXPECT_IMAGE_ID (optional)
# = the exact image id build-image.sh printed. A stale local image of the same tag, or one rebuilt elsewhere, is refused (return 4).
flyway_verify_image() {
  [ -n "${FW_EXPECT_REVISION:-}" ] || [ -n "${FW_EXPECT_IMAGE_ID:-}" ] || return 0
  if [ -n "${FW_EXPECT_REVISION:-}" ]; then
    _fw_rev="$("${DOCKER:-docker}" image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$FW_IMAGE" 2>/dev/null)" || _fw_rev=""
    case "$_fw_rev" in
      "") echo "ERROR: $FW_IMAGE has no org.opencontainers.image.revision label - not built by flyway/build-image.sh; refusing to give it the database password" >&2; return 4 ;;
      "$FW_EXPECT_REVISION"*) ;;
      *) echo "ERROR: $FW_IMAGE was built from commit $_fw_rev, not $FW_EXPECT_REVISION (a stale or foreign image under this tag) - rebuild with flyway/build-image.sh; refusing to give it the database password" >&2; return 4 ;;
    esac
  fi
  if [ -n "${FW_EXPECT_IMAGE_ID:-}" ]; then
    _fw_id="$("${DOCKER:-docker}" image inspect --format '{{.Id}}' "$FW_IMAGE" 2>/dev/null)" || _fw_id=""
    [ "$_fw_id" = "$FW_EXPECT_IMAGE_ID" ] || { echo "ERROR: $FW_IMAGE has image id $_fw_id, expected $FW_EXPECT_IMAGE_ID; refusing to give it the database password" >&2; return 4; }
  fi
}
FW_SECRET_DIR=""
flyway_cleanup() { if [ -n "$FW_SECRET_DIR" ]; then rm -rf "$FW_SECRET_DIR"; fi; FW_SECRET_DIR=""; }

flyway_run() { # flyway arguments (flags, then the action)
  flyway_verify_image || return $?
  if [ "${IPASS_FLYWAY_TEST:-}" != 1 ] && [ -n "${FW_DOCKER_ARGS:-}${FW_RUN_USER:-}${FW_SECRET_BASE:-}" ]; then
    echo "ERROR: FW_DOCKER_ARGS, FW_RUN_USER and FW_SECRET_BASE are for tests only (they would weaken the container flags); refused" >&2; return 2
  fi
  case "${FW_RUN_USER:-}" in 0|0:*|root|root:*) echo "ERROR: FW_RUN_USER must not be uid 0" >&2; return 2 ;; esac
  _fw_um="$(umask)"; umask 077
  _fw_root=0; if [ "$(id -u)" = 0 ]; then _fw_root=1; fi
  if [ -n "${FW_SECRET_BASE:-}" ]; then _fw_base="$FW_SECRET_BASE"
  elif [ "$_fw_root" = 1 ]; then _fw_base=/run/ipass-flyway
  else _fw_base="${TMPDIR:-/tmp}"; fi
  if [ "$_fw_root" = 1 ]; then
    mkdir -p "$_fw_base" && chmod 700 "$_fw_base" && [ "$(stat -c %u "$_fw_base")" = 0 ] || { umask "$_fw_um"; echo "ERROR: $_fw_base is not a root-owned directory I can use" >&2; return 2; }
  fi
  FW_SECRET_DIR="$(mktemp -d "$_fw_base/flyway-secret.XXXXXX")" || { umask "$_fw_um"; echo "ERROR: cannot create the secret directory" >&2; return 2; }
  chmod 700 "$FW_SECRET_DIR"
  _fw_esc="$(printf '%s' "$FW_DB_PASSWORD" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
  printf '[environments.default]\npassword = "%s"\n' "$_fw_esc" > "$FW_SECRET_DIR/secret.toml"
  chmod 600 "$FW_SECRET_DIR/secret.toml"
  if [ -n "${FW_RUN_USER:-}" ]; then _fw_user="$FW_RUN_USER"
  elif [ "$_fw_root" != 1 ]; then _fw_user="$(id -u):$(id -g)"
  else
    _fw_user="61001:61001"
    chown 61001:61001 "$FW_SECRET_DIR" "$FW_SECRET_DIR/secret.toml" || { umask "$_fw_um"; flyway_cleanup; echo "ERROR: cannot hand the secret file to uid 61001" >&2; return 2; }
  fi
  _fw_mount="$FW_SECRET_DIR"; if command -v cygpath >/dev/null 2>&1; then _fw_mount="$(cygpath -w "$FW_SECRET_DIR")"; fi
  # shellcheck disable=SC2086   # FW_DOCKER_ARGS is deliberately split
  MSYS_NO_PATHCONV=1 "${DOCKER:-docker}" run --rm --network "$FW_NETWORK" \
    --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m --cap-drop ALL --security-opt no-new-privileges:true --pids-limit 256 --memory 768m --user "$_fw_user" \
    -e HOME=/tmp -e "FLYWAY_URL=$FW_URL" -e "FLYWAY_USER=$FW_DB_USER" -v "$_fw_mount:/run/flyway-secret:ro" ${FW_DOCKER_ARGS:-} \
    "$FW_IMAGE" -configFiles=/run/flyway-secret/secret.toml "$@"
  _fw_rc=$?
  umask "$_fw_um"      # restored only now: on Windows (Git Bash) file modes are shown through the current umask
  flyway_cleanup
  return "$_fw_rc"
}
