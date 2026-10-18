#!/bin/bash
# Row 323 (F21) Tue 20 base-version run on the FROZEN candidate sha (aditya-qa-automation, T129/T130).
# Modelled on _reports/row323-base-suite-20261020/t129-run/t129-run.sh, minus the lock wait:
# this script NEVER takes, waits for or releases the docker-suite lock. The Tue lock owner runs it
# while holding the lock; it refuses to start unless the lock's owner file names the caller.
#
# Usage: LOCK_OWNER=<owner written by lock.sh take> bash run-tue.sh <candidate-sha> [--with-base-suite]
#   T129 steps (BS-01..BS-06) always; --with-base-suite then checks BS-11 is listed and runs BS-07..BS-12
#   (needs all four IPASS_BS_EXCEPTION_PROFILE / _HOST_VAR / _HOST / _PORT).
# Logs: <evidence>/t129-run/t129-<short>-<step>.log + t129-<short>-summary.txt. Exit 0 = every step rc=0.
set -u
SHA_IN=${1:-}
WITH_BS=${2:-}
RUN=/d/AI_Agents/AI-Agent-Team/_personal/_project_ipass/integration-platform/_reports/row323-base-suite-20261020/t129-run
CODE=/d/Projects/ipass/integration-platform
L=/d/Projects/ipass/.docker-suite.lock
DUMMY=/d/_Working_tree/t129-bs-a-docker-lock-dummy
mkdir -p "$RUN"
WLOG=$RUN/run-tue.log
log() { echo "$(date -u +%FT%TZ) $*" | tee -a "$WLOG"; }

[ -n "$SHA_IN" ] || { echo "usage: LOCK_OWNER=<owner> bash run-tue.sh <candidate-sha> [--with-base-suite]" >&2; exit 2; }
case "$WITH_BS" in ''|--with-base-suite) ;; *) echo "unknown option $WITH_BS" >&2; exit 2;; esac
SHA=$(git -C "$CODE" rev-parse --verify "${SHA_IN}^{commit}" 2>/dev/null) || { log "FATAL $SHA_IN is not a commit in $CODE"; exit 2; }
SHORT=${SHA:0:7}

# ---------- 1. the lock must already be held by the caller (never taken here) ----------
: "${LOCK_OWNER:?set LOCK_OWNER to the owner name you took the lock with}"
HELD=$(cat "$L/owner" 2>/dev/null | head -1)
if [ -z "$HELD" ] || ! printf '%s' "$HELD" | grep -qF -- "$LOCK_OWNER"; then
  log "REFUSED lock not held by $LOCK_OWNER (owner-now: ${HELD:-none}) - take it with lock.sh first; not running"
  exit 3
fi
log "START sha $SHA lock held by: $HELD"

# ---------- 2. export the candidate (git archive, never the live tree) ----------
EXPORT=/d/_Working_tree/t129-bs-a-$SHORT
if [ -e "$EXPORT/ui/node_modules" ]; then cmd //c rmdir "$(cygpath -w "$EXPORT/ui/node_modules")" >> "$WLOG" 2>&1; fi  # junction first, never through it
rm -rf "$EXPORT"; mkdir -p "$EXPORT"
git -C "$CODE" archive --format=tar "$SHA" | tar -x -C "$EXPORT" || { log "export failed"; exit 6; }
cmd //c mklink //J "$(cygpath -w "$EXPORT/ui/node_modules")" "$(cygpath -w "$CODE/ui/node_modules")" >> "$WLOG" 2>&1
log "export $EXPORT ready"

export JAVA_TOOL_OPTIONS=-Duser.timezone=Etc/UTC
export MAVEN_OPTS=-Xmx768m
export JAVA_HOME="$(cygpath -u "$USERPROFILE")/.jdks/jdk-25.0.4.1+1"
[ -x "$JAVA_HOME/bin/java" ] || { log "FATAL no java at $JAVA_HOME"; exit 91; }
# the outer lock is held by the caller; the inner harness gets a private dummy so it never contends with it
export DOCKER_SUITE_LOCK="$(cygpath -m "$DUMMY")"
rm -rf "$DUMMY"
trap 'rm -rf "$DUMMY"; log "EXIT rc=$? (lock left with its owner)"' EXIT
trap 'exit 143' TERM INT HUP

SUM=$RUN/t129-$SHORT-summary.txt
echo "sha $SHA export $EXPORT started $(date -u +%FT%TZ)" > "$SUM"
FAILED=0
step() { # name timeout cwd cmd...
  local name=$1 to=$2 dir=$3; shift 3
  local lg=$RUN/t129-$SHORT-$name.log
  log "step $name start"
  ( cd "$dir" && timeout --kill-after=60s "$to" "$@" ) > "$lg" 2>&1; local r=$?
  [ $r -eq 0 ] || FAILED=$((FAILED+1))
  log "step $name rc=$r log=$lg"; echo "$name rc=$r $lg" >> "$SUM"
}

# ---------- 3. T129: BS-01..BS-06 (same steps as the a4cfcca dry run, all rc=0 there) ----------
JAVA_HOME="$(cygpath -m "$JAVA_HOME")" step bs01-05-screens 45m "$EXPORT/ui" npm run test:screens -- login.docker.spec.mjs flow-definition.docker.spec.mjs mapping-designer.docker.spec.mjs lifecycle.docker.spec.mjs
step bs04-t037 30m "$EXPORT" ./mvnw -B -Pcontainer -pl platform-config -am test -Dtest=T037SampleRunGateContainerTest -Dsurefire.failIfNoSpecifiedTests=false
step bs05-t019 30m "$EXPORT" ./mvnw -B -Pcontainer -pl platform-config -am test -Dtest=T019ConfigLifecycleContainerTest -Dsurefire.failIfNoSpecifiedTests=false
step bs06-intake-commit 30m "$EXPORT" ./mvnw -B -Pcontainer -pl platform-routing -am test -Dtest=IntakeCommitContainerTest -Dsurefire.failIfNoSpecifiedTests=false
step bs06-intake-shape 20m "$EXPORT" ./mvnw -B -pl platform-ingest -am test -Dtest=IntakeEndpointShapeTest -Dsurefire.failIfNoSpecifiedTests=false

# ---------- 4. optional: the base suite BS-07..BS-12 (fixme specs report as fixme, never pass) ----------
if [ "$WITH_BS" = --with-base-suite ]; then
  MISSING=""
  # BS-11 (T133): the spec is in the base-suite config already - this step only proves it is listed (no docker)
  step bs11-listed 5m "$EXPORT/ui" bash -c 'npx playwright test --config playwright.base-suite.config.mjs --list | tee /dev/stderr | grep -q "bs-11\.docker\.spec\.mjs"'
  for v in IPASS_BS_EXCEPTION_PROFILE IPASS_BS_EXCEPTION_HOST_VAR IPASS_BS_EXCEPTION_HOST IPASS_BS_EXCEPTION_PORT; do
    [ -n "${!v:-}" ] || MISSING="$MISSING $v"
  done
  if [ -n "$MISSING" ]; then
    log "base-suite NOT RUN: unset$MISSING"; echo "base-suite rc=SKIPPED unset$MISSING" >> "$SUM"; FAILED=$((FAILED+1))
  else
    JAVA_HOME="$(cygpath -m "$JAVA_HOME")" step base-suite 60m "$EXPORT/ui" npx playwright test --config playwright.base-suite.config.mjs --reporter=line --trace on
  fi
fi

echo "finished $(date -u +%FT%TZ) failed_steps=$FAILED" >> "$SUM"
log "ALL STEPS DONE failed_steps=$FAILED summary=$SUM"
[ $FAILED -eq 0 ]
