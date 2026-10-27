#!/bin/sh
# The ONE place that starts the Flyway container (sourced by flyway/migrate.sh, which deploy.sh calls, and by test/flyway-local-proof.sh).
#   FW_IMAGE FW_NETWORK FW_URL FW_DB_USER FW_DB_PASSWORD   required; DOCKER = the docker binary (default docker)
#   FW_DOCKER_ARGS  optional extra `docker run` arguments (tests only, e.g. --name x -v dir:/probe:ro), split on blanks
#   FW_RUN_USER     optional uid:gid for the container (default: the calling user, or 1001:1001 when run as root)
# Secret handling: the password is written to a mode-600 TOML file in a mode-700 temp directory (mktemp, under $TMPDIR or /tmp),
# mounted read-only at /run/flyway-secret and handed to Flyway with -configFiles. It is NEVER an environment variable (those show
# in `docker inspect` Config.Env), never a command-line argument (those show in the host process list). The directory is removed
# as soon as the container exits, and by flyway_cleanup (callers trap EXIT/INT/TERM with it) when the script is interrupted.
# Container hardening: read-only root file system with a small tmpfs /tmp (Flyway needs HOME and temp space only), all
# capabilities dropped, no-new-privileges, non-root user (the caller's uid so the 600 file is readable; root callers use 1001,
# the uid that owns /flyway in the image, and the temp directory is handed to it for the run).
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
  _fw_um="$(umask)"; umask 077
  FW_SECRET_DIR="$(mktemp -d "${TMPDIR:-/tmp}/flyway-secret.XXXXXX")" || { umask "$_fw_um"; echo "ERROR: cannot create the secret directory" >&2; return 2; }
  chmod 700 "$FW_SECRET_DIR"
  _fw_esc="$(printf '%s' "$FW_DB_PASSWORD" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
  printf '[environments.default]\npassword = "%s"\n' "$_fw_esc" > "$FW_SECRET_DIR/secret.toml"
  chmod 600 "$FW_SECRET_DIR/secret.toml"
  if [ -n "${FW_RUN_USER:-}" ]; then _fw_user="$FW_RUN_USER"
  elif [ "$(id -u)" != 0 ]; then _fw_user="$(id -u):$(id -g)"
  else _fw_user="1001:1001"; chown -R 1001:1001 "$FW_SECRET_DIR"; fi
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
