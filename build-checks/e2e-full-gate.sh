#!/usr/bin/env bash
# T-175 (E18-F1-S1): the project-close gate - ONE command runs the full end-to-end integration suite and decides.
#
#   build-checks/e2e-full-gate.sh            run the suite (Maven profile e2e-full), then judge it
#   build-checks/e2e-full-gate.sh verdict F  judge an outcomes file F only (no Docker; used by test-e2e-full-gate.sh)
#
# Equivalent Maven line (the gate adds the Docker lock and the completeness verdict around it):
#   ./mvnw -o -Pe2e-full -pl platform-app -am test -Dsurefire.failIfNoSpecifiedTests=false
#
# GREEN only when EVERY required scenario in build-checks/e2e-full-required.txt has a PASS line in
# platform-app/target/e2e-full/outcomes.tsv. FAIL, BLOCKED, ABORTED, DISABLED and NOT_RUN (no line) are all red:
# one red target type blocks the close (E18-F1-S1 @negative), and a run that skipped a scenario never closes.
# Exit 0 = the project may close on this commit; 1 = it may not; 2 = the gate could not run.
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root" || exit 2
required="$root/build-checks/e2e-full-required.txt"
outcomes="$root/platform-app/target/e2e-full/outcomes.tsv"

verdict() {
  local file="$1" red=0 total=0 green=0 id status line
  [ -f "$required" ] || { echo "gate: $required is missing"; return 2; }
  while IFS= read -r id; do
    case "$id" in ''|'#'*) continue ;; esac
    total=$((total + 1))
    # the LAST line for a scenario wins (a re-run appends)
    line="$(grep -F -- "${id}"$'\t' "$file" 2>/dev/null | tail -n 1)"
    status="$(printf '%s' "$line" | cut -f2)"
    [ -n "$status" ] || status="NOT_RUN"
    if [ "$status" = "PASS" ]; then
      green=$((green + 1))
      printf 'GREEN   %s\n' "$id"
    else
      red=$((red + 1))
      printf 'RED     %-12s %s  %s\n' "$status" "$id" "$(printf '%s' "$line" | cut -f3 | cut -c1-200)"
    fi
  done < "$required"
  echo "e2e-full verdict: ${green}/${total} required scenarios PASS, ${red} red"
  if [ "$red" -eq 0 ] && [ "$total" -gt 0 ]; then
    echo "GATE: PASS - the full suite is green on $(git rev-parse --short HEAD 2>/dev/null)"
    return 0
  fi
  echo "GATE: FAIL - the project does not close"
  return 1
}

if [ "${1:-}" = "verdict" ]; then
  [ $# -eq 2 ] || { echo "usage: $0 verdict <outcomes.tsv>"; exit 2; }
  verdict "$2"
  exit $?
fi

# R04: UTC zone on every Maven run. R06: capped Maven JVM unless the caller set its own.
case "${JAVA_TOOL_OPTIONS:-}" in
  *user.timezone=*) ;;
  *) export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.timezone=Etc/UTC" ;;
esac
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx768m}"

# R06: the one PC-wide Docker lock beside the main checkout (also from a worktree). Waits, never takes over.
common="$(cd "$(git rev-parse --git-common-dir)" && pwd)"
# L22: first free of the four slots (.docker-suite.lock, -1, -2, -3) so this gate can overlap a UI suite, the Release 1
# gate or a -Pcontainer Maven suite; DOCKER_SUITE_LOCK, when set, is the only candidate. Same protocol per slot:
# mkdir = take, owner file = who, a held slot is never touched, release removes only the slot this run took.
slot_base="$(dirname "$(dirname "$common")")/.docker-suite.lock"
if [ -n "${DOCKER_SUITE_LOCK:-}" ]; then slots=("$DOCKER_SUITE_LOCK"); else slots=("$slot_base" "$slot_base-1" "$slot_base-2" "$slot_base-3"); fi
lock="${slots[0]}"
take_slot() {
  local s
  for s in "${slots[@]}"; do
    if mkdir "$s" 2>/dev/null; then lock="$s"; return 0; fi
  done
  return 1
}
have_lock=0
release() { [ "$have_lock" = 1 ] && rm -rf "$lock"; have_lock=0; }
trap release EXIT
trap 'exit 130' INT TERM
waited=0
until take_slot; do
  if [ "$waited" -ge "${E2E_FULL_LOCK_WAIT_SECONDS:-4500}" ]; then
    echo "gate: every Docker slot stayed held for ${waited} s (${slots[*]}; first: $(cat "${slots[0]}/owner" 2>/dev/null)) - not run"
    exit 2
  fi
  sleep 60
  waited=$((waited + 60))
done
have_lock=1
echo "${E2E_FULL_LOCK_OWNER:-e2e-full-gate} $(date -u +%FT%TZ)" > "$lock/owner"

rm -rf "$root/platform-app/target/e2e-full"
./mvnw -B -o -Pe2e-full -pl platform-app -am test -Dsurefire.failIfNoSpecifiedTests=false "$@"
mvn_rc=$?
release
echo "gate: maven exit ${mvn_rc}"
[ -f "$outcomes" ] || { echo "gate: no outcomes file - the suite did not run"; exit 1; }
verdict "$outcomes"
rc=$?
[ "$mvn_rc" -ne 0 ] && [ "$rc" -eq 0 ] && { echo "GATE: FAIL - maven failed although every scenario passed (see the log)"; rc=1; }
exit $rc
