#!/bin/bash
# T132 (row 323, F21 BS-13, karan-security): the two-tenant safety run - the release-gate attack pack
# (spec release-suite-base-20261022.md, section "The two-tenant safety run (BS-13)"), authored
# runnable against the integration tip feat/e9-e16 d5e2c6b. Modelled on run-tue.sh, minus the base
# suite: this script NEVER takes, waits for or releases the docker-suite lock. The lock's owner runs
# it while holding the lock; it refuses to start otherwise.
#
# Usage: LOCK_OWNER=<owner written by lock.sh take> bash run-bs13.sh <candidate-sha> [leg ...]
#   Legs (default all, in this order):
#     13a  forged-tenant denial suite + endpoint-coverage guard (every /api/** matrix row)
#     13b  RLS gate        + RLS gate fail-closed, no pooled-setting leak
#     13c  pipeline two-tenant - tenant B never sees A's message/payload/delivery/timing rows
#     13d  new tables and reads - tracking reads + the masked journey (T108's own container tests)
#     13e  stage path - ids-only keys, forged header/value on a record changes nothing (S6-A1);
#          the horizontal timeline/failures/replay sweep is 13a's E13/T111/E12 matrix rows
#     13f  one failing target cannot harm the other tenant (T-118 best-effort isolation)
#   Red 13a guard or red 13b = no-go (spec section "Go / no-go signals").
# Evidence: _reports/row323-base-suite-20261020/bs13-run/<short>/bs13-<leg>.log + -summary.txt.
# Every leg rc=0 = exit 0 (green); any rc!=0 = exit 1.
set -u
RUN=/d/AI_Agents/AI-Agent-Team/_personal/_project_ipass/integration-platform/_reports/row323-base-suite-20261020/bs13-run
CODE=/d/Projects/ipass/integration-platform
L=/d/Projects/ipass/.docker-suite.lock
DUMMY=/d/_Working_tree/bs13-docker-lock-dummy
log() { echo "$(date -u +%FT%TZ) $*" | tee -a "$RUN/bs13-run.log"; }

[ $# -ge 1 ] || { echo "usage: LOCK_OWNER=<owner> bash run-bs13.sh <candidate-sha> [leg ...]" >&2; exit 2; }
SHA_IN=$1; shift
case "${LOCK_OWNER:-}" in '') echo "LOCK_OWNER=<owner> required (the script never takes the lock)" >&2; exit 2;; esac
SHA=$(git -C "$CODE" rev-parse --verify "${SHA_IN}^{commit}") || { log "FATAL $SHA_IN is not a commit in $CODE"; exit 2; }
SHORT=${SHA:0:7}; OUT=$RUN/$SHORT; mkdir -p "$OUT"

HELD=$(cat "$L/owner" 2>/dev/null | head -1)
case "$HELD" in *"$LOCK_OWNER"*) ;; *) log "REFUSED the docker-suite lock is not held by $LOCK_OWNER (owner-now: ${HELD:-none}) - take it with lock.sh first"; exit 3;; esac
log "START sha $SHA (lock held by $HELD)"

# the candidate is exported from git archive, never the live tree; node_modules junction like run-tue.sh
EXPORT=/d/_Working_tree/bs13-$SHORT
rm -rf "$EXPORT"; mkdir -p "$EXPORT"
git -C "$CODE" archive --format=tar "$SHA" | tar -x -C "$EXPORT" || { log "FATAL export failed"; exit 6; }
cmd //c mklink //J "$(cygpath -w "$EXPORT/ui/node_modules")" "$(cygpath -w "$CODE/ui/node_modules")" >/dev/null 2>&1 || log "WARN node_modules junction failed (container legs do not need it)"
export JAVA_TOOL_OPTIONS=-Duser.timezone=Etc/UTC MAVEN_OPTS=-Xmx768m
export JAVA_HOME="$(cygpath -u "$USERPROFILE")/.jdks/jdk-25.0.4.1+1"
[ -x "$JAVA_HOME/bin/java" ] || { log "FATAL no java at $JAVA_HOME"; exit 91; }
# the outer lock is held by the caller; the inner harness gets a private dummy so it never contends
export DOCKER_SUITE_LOCK="$(cygpath -m "$DUMMY")"
rm -rf "$DUMMY"
trap 'rm -rf "$DUMMY"; log "EXIT rc=$? (lock left with its owner)"' EXIT
trap 'exit 143' TERM INT HUP

WAIT='platform-routing StageConsumerTest,StageCorrelationTest,StageCorrelationContainerTest,StageProtectedPathContainerTest'
leg() { # id module tests
  local id=$1 mod=$2 tests=$3 lg="$OUT/bs13-$1.log"
  log "leg $id start: $mod $tests"
  (cd "$EXPORT" && ./mvnw -o -pl "$mod" -am -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false -Pcontainer test) > "$lg" 2>&1
  local rc=$?
  grep -E "Tests run: .*Failures: [1-9]|Errors: [1-9]|BUILD FAILURE|BUILD SUCCESS" "$lg" | tail -2 >> "$lg"  # tail keeps the verdict lines
  log "leg $id rc=$rc ($lg)"
  return $rc
}

FAILED=0
SUM="$OUT/bs13-$SHORT-summary.txt"
: > "$SUM"
for spec in ${BS13_LEGS:-13a 13b 13c 13d 13e 13f}; do
  case "$spec" in
    13a) leg "$spec" platform-app ForgedTenantDenialSuiteTest ;;
    13b) leg "$spec" platform-tenancy RowLevelSecurityGateTest ;;
    13c) leg "$spec" platform-app DipeshExampleEndToEndContainerTest ;;
    13d) leg "$spec" platform-app MessageTrackingMaskingTest,MaskedFieldsJourneyContainerTest ;;
    13e) leg "$spec" ${WAIT% *} ${WAIT#* } ;;
    13f) leg "$spec" platform-app BestEffortIsolationContainerTest ;;
    *) log "FATAL unknown leg $spec (13a..13f)"; exit 2 ;;
  esac
  rc=$?
  printf '%s rc=%s\n' "$spec" "$rc" >> "$SUM"
  case $rc in '' | *[!0-9]*) rc=1;; esac  # any non-zero-exit leg marks the pack red
  [ "$rc" != 0 ] && { FAILED=1; log "leg $spec RED - see $OUT/bs13-$spec.log"; }
done
log "DONE FAILED=$FAILED summary $SUM"
exit $FAILED