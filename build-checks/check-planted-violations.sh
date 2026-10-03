#!/usr/bin/env bash
# R02/R12 planted-violation check (T-087, scenario "A second kafka-clients version fails the build").
# Each build-checks/planted-*/pom.xml inherits the root parent pom and declares ONE forbidden
# dependency. The check passes only when every planted build FAILS at validate and the output
# names the maven-enforcer rule that stopped it.
# Usage: build-checks/check-planted-violations.sh   (from anywhere; needs no Docker)
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root" || exit 2

if [ ! -x ./mvnw ] || [ ! -f ./pom.xml ]; then
  echo "FAIL: no Maven build here (./mvnw or ./pom.xml missing)"
  exit 1
fi

planted=(planted-kafka-4.2.1 planted-spring-kafka planted-second-networknt)
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
fail=0
run=0

for p in "${planted[@]}"; do
  run=$((run + 1))
  pom="build-checks/$p/pom.xml"
  if [ ! -f "$pom" ]; then
    echo "FAIL: $p - fixture $pom missing"; fail=$((fail + 1)); continue
  fi
  ./mvnw -B -f "$pom" validate > "$log" 2>&1
  rc=$?
  if [ "$rc" -eq 0 ]; then
    echo "FAIL: $p - build passed, the forbidden dependency was not stopped"
    fail=$((fail + 1))
  elif grep -qE 'enforcer\.rules\.dependency\.BannedDependencies|BannedDependencies failed' "$log"; then
    echo "PASS: $p - build failed (exit $rc) on the enforcer rule: $(grep -oE 'Rule [0-9]+: [^ ]+ failed' "$log" | head -n 1)"
  else
    echo "FAIL: $p - build failed (exit $rc) but not on the enforcer rule"
    tail -n 20 "$log"
    fail=$((fail + 1))
  fi
done

echo "planted violations: run=$run failed-checks=$fail"
[ "$fail" -eq 0 ]
