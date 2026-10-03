#!/usr/bin/env bash
# R03 container-group check (T-088, scenario "A bare test run does not start containers").
#   bare      : ./mvnw test -pl platform-app -am must NOT run ApplicationStartsAgainstPostgresTest and must start no
#               Testcontainers container.
#   container : ./mvnw -Pcontainer test -pl platform-app -am must run ApplicationStartsAgainstPostgresTest and pass.
# Usage: build-checks/check-container-exclusion.sh [bare|container|all] [extra maven args for the bare run...]
#   e.g. planted violation (exclusion switched off): build-checks/check-container-exclusion.sh bare -Dsurefire.excludedGroups=
# Both parts hold the one-Docker-suite-at-a-time lock (R06), so a broken exclusion still cannot start a second suite.
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root" || exit 2

mode="${1:-all}"
[ $# -gt 0 ] && shift
case "$mode" in bare|container|all) ;; *) echo "usage: $0 [bare|container|all] [maven args...]"; exit 2 ;; esac

# R04: every Maven run carries the UTC zone (the PostgreSQL container rejects the build PC's legacy zone).
case "${JAVA_TOOL_OPTIONS:-}" in
  *user.timezone=*) ;;
  *) export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.timezone=Etc/UTC" ;;
esac
# R06: capped Maven JVM unless the caller set its own.
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx384m -Xms64m -XX:MaxMetaspaceSize=256m -Xshare:off}"

proof="ApplicationStartsAgainstPostgresTest"
reports="platform-app/target/surefire-reports"
# The PC-wide lock sits beside the main checkout (D:/Projects/ipass/.docker-suite.lock), also from a worktree.
common="$(cd "$(git rev-parse --git-common-dir)" && pwd)"
lock="${DOCKER_SUITE_LOCK:-$(dirname "$(dirname "$common")")/.docker-suite.lock}"
out="$(mktemp -d)"
have_lock=0

cleanup() {
  [ "$have_lock" = 1 ] && rm -rf "$lock"
  rm -rf "$out"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

take_lock() {
  until mkdir "$lock" 2>/dev/null; do
    if [ -f "$lock/owner" ] && [ -n "$(find "$lock/owner" -mmin +60 2>/dev/null)" ]; then
      echo "NOTE: removing stale Docker lock (owner older than 60 min): $(cat "$lock/owner" 2>/dev/null)"
      rm -rf "$lock"
      continue
    fi
    sleep 20
  done
  have_lock=1
  echo "check-container-exclusion $(date -u)" > "$lock/owner"
}

release_lock() {
  rm -rf "$lock"
  have_lock=0
}

run_bare() {
  rm -rf "$reports"
  local log="$out/bare.log" rc
  ./mvnw -B test -pl platform-app -am "$@" > "$log" 2>&1
  rc=$?
  local ran started tests
  ran="$(ls "$reports" 2>/dev/null | grep -c "$proof")"
  started="$(grep -cE 'Creating container for image|Container .* started in' "$log")"
  tests="$(grep -E '^\[(INFO|WARNING|ERROR)\] Tests run:' "$log" | tail -n 1)"
  echo "bare run: mvn exit ${rc}; ${tests:-no test summary}; ${proof} reports: ${ran}; container starts in log: ${started}"
  if [ "$ran" -ne 0 ] || [ "$started" -ne 0 ]; then
    echo "FAIL: a bare test run ran the container group (R03)"
    grep -E 'Creating container for image|Tests run:.*'"$proof" "$log" | head -n 5
    return 1
  fi
  if [ "$rc" -ne 0 ]; then
    echo "FAIL: bare ./mvnw test failed"
    tail -n 30 "$log"
    return 1
  fi
  echo "PASS: bare test run started no container"
}

run_container() {
  rm -rf "$reports"
  local log="$out/container.log" rc xml
  ./mvnw -B -Pcontainer test -pl platform-app -am > "$log" 2>&1
  rc=$?
  xml="$reports/TEST-io.integrationplatform.$proof.xml"
  local summary=""
  [ -f "$xml" ] && summary="$(grep -oE '<testsuite [^>]*' "$xml" | grep -oE '(tests|failures|errors|skipped)="[0-9]+"' | tr '\n' ' ')"
  echo "container run: mvn exit ${rc}; ${proof}: ${summary:-no report}"
  if [ "$rc" -ne 0 ] || [ ! -f "$xml" ]; then
    echo "FAIL: -Pcontainer did not run ${proof} to success"
    tail -n 30 "$log"
    return 1
  fi
  if ! grep -qE '<testsuite [^>]*tests="[1-9]' "$xml" \
     || grep -qE '<testsuite [^>]*(failures|errors|skipped)="[1-9]' "$xml"; then
    echo "FAIL: ${proof} did not pass every test"
    return 1
  fi
  echo "PASS: -Pcontainer ran ${proof} and it passed"
}

status=0
if [ "$mode" = bare ] || [ "$mode" = all ]; then
  take_lock
  run_bare "$@" || status=1
  release_lock
fi
if [ "$mode" = container ] || [ "$mode" = all ]; then
  take_lock
  run_container || status=1
  release_lock
fi
[ "$status" -eq 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL"
exit "$status"
