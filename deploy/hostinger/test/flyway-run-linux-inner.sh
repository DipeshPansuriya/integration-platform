#!/bin/sh
# Runs INSIDE a Linux container (started by test/flyway-run-linux.sh): drives flyway/flyway-run.sh with a stub `docker`
# that records what the real one would have received and what the secret file looked like at that moment.
# Env: H = path of deploy/hostinger inside the container, PW = throwaway password. Prints PASS/FAIL lines; exit 1 on any FAIL.
fail=0
ok() { echo "PASS $1"; }
bad() { echo "FAIL $1"; fail=1; }
check() { if (eval "$2"); then ok "$1"; else bad "$1"; fi; }
ME="$(id -u)"
OUT=/tmp/out; STUB=/tmp/stub; rm -rf "$OUT" "$STUB"; mkdir -p "$OUT" "$STUB"
cat > "$STUB/docker" <<'EOF'
#!/bin/sh
echo "$*" > /tmp/out/args
for a in "$@"; do case "$a" in *:/run/flyway-secret:ro)
  d="${a%:/run/flyway-secret:ro}"; echo "$d" > /tmp/out/dir
  echo "dir=$(stat -c '%a %u:%g' "$d") file=$(stat -c '%a %u:%g' "$d/secret.toml")" > /tmp/out/modes
  grep -qF "$PW" "$d/secret.toml" && echo yes > /tmp/out/filehaspw ;; esac; done
if [ "$MODE" = term ]; then kill -TERM "$PPID"; sleep 3; fi
exit 0
EOF
chmod +x "$STUB/docker"
. "$H/flyway/flyway-run.sh"
export PW MODE DOCKER="$STUB/docker" IPASS_FLYWAY_TEST=1
run() { # mode: normal | term   (the trap lines are the ones migrate.sh installs)
  MODE="$1"; rm -f "$OUT"/*
  ( trap flyway_cleanup EXIT; trap 'flyway_cleanup; exit 130' INT TERM
    FW_IMAGE=img FW_NETWORK=net FW_URL=jdbc:postgresql://postgres:5432/db FW_DB_USER=u FW_DB_PASSWORD="$PW" flyway_run info )
  echo $? > "$OUT/rc"
}

echo "== caller uid $ME"
run normal
D="$(cat "$OUT/dir")"
if [ "$ME" = 0 ]; then
  check "root: secret dir is under the root-owned /run/ipass-flyway, not TMPDIR" 'case "$D" in /run/ipass-flyway/*) true;; *) false;; esac'
  check "root: the /run/ipass-flyway base is mode 700 owned by root" '[ "$(stat -c "%a %u" /run/ipass-flyway)" = "700 0" ]'
  check "root: dir 700 and file 600, both owned by 61001:61001 (non-recursive chown of what the script created)" 'grep -q "dir=700 61001:61001 file=600 61001:61001" "$OUT/modes"'
  check "root: the container user is 61001:61001" 'grep -q -- "--user 61001:61001" "$OUT/args"'
  check "root: uid 61001 does not exist on this host (no passwd entry)" '! grep -q "^[^:]*:[^:]*:61001:" /etc/passwd'
else
  check "non-root: secret dir is under TMPDIR (/tmp)" 'case "$D" in /tmp/flyway-secret.*) true;; *) false;; esac'
  check "non-root: dir 700 and file 600, owned by the caller $ME" 'grep -q "dir=700 $ME:[0-9]* file=600 $ME:[0-9]*" "$OUT/modes"'
  check "non-root: the container user is the caller (so the 600 file is readable)" 'grep -qE -- "--user $ME:[0-9]+ " "$OUT/args"'
fi
check "the file held the password when the container started" '[ -f "$OUT/filehaspw" ]'
check "the password is absent from the docker argument list" '! grep -qF "$PW" "$OUT/args" && ! grep -q FLYWAY_PASSWORD "$OUT/args"'
check "hardening flags present" 'grep -q -- "--read-only" "$OUT/args" && grep -q -- "--cap-drop ALL" "$OUT/args" && grep -q -- "no-new-privileges:true" "$OUT/args" && grep -q -- "-configFiles=/run/flyway-secret/secret.toml" "$OUT/args"'
check "the secret directory is gone after a normal exit" '[ ! -e "$D" ]'

run term
D="$(cat "$OUT/dir" 2>/dev/null)"
check "trap path: the container (stub) was terminated mid-run, rc 130" '[ "$(cat "$OUT/rc")" = 130 ]'
check "trap path: the secret directory is gone after SIGTERM" '[ -n "$D" ] && [ ! -e "$D" ]'

# refusals: test-only variables are ignored in production, uid 0 never allowed
unset IPASS_FLYWAY_TEST; rm -f "$OUT"/args
( FW_IMAGE=img FW_NETWORK=net FW_URL=u FW_DB_USER=u FW_DB_PASSWORD="$PW" FW_DOCKER_ARGS="--privileged" flyway_run info ) >/dev/null 2>&1; rc=$?
check "FW_DOCKER_ARGS without IPASS_FLYWAY_TEST=1 is refused (rc 2), docker never called" '[ "$rc" = 2 ] && [ ! -e "$OUT/args" ]'
( FW_IMAGE=img FW_NETWORK=net FW_URL=u FW_DB_USER=u FW_DB_PASSWORD="$PW" FW_RUN_USER=1:1 flyway_run info ) >/dev/null 2>&1; rc=$?
check "FW_RUN_USER without IPASS_FLYWAY_TEST=1 is refused (rc 2)" '[ "$rc" = 2 ] && [ ! -e "$OUT/args" ]'
export IPASS_FLYWAY_TEST=1
( FW_IMAGE=img FW_NETWORK=net FW_URL=u FW_DB_USER=u FW_DB_PASSWORD="$PW" FW_RUN_USER=0:0 flyway_run info ) >/dev/null 2>&1; rc=$?
check "FW_RUN_USER=0:0 is refused even in test mode (rc 2)" '[ "$rc" = 2 ] && [ ! -e "$OUT/args" ]'
[ "$fail" = 0 ] && echo "INNER OK (uid $ME)"
exit "$fail"
