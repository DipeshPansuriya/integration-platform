#!/usr/bin/env bash
# Proves the project-close gate's verdict without Docker (E18-F1-S1 @negative "One red target type blocks the close").
# Usage: build-checks/test-e2e-full-gate.sh   - exit 0 when all three cases behave.
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
gate="$root/build-checks/e2e-full-gate.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail=0

all_pass() { grep -vE '^(#|$)' "$root/build-checks/e2e-full-required.txt" | while IFS= read -r id; do printf '%s\tPASS\t\n' "$id"; done; }

all_pass > "$tmp/green.tsv"
bash "$gate" verdict "$tmp/green.tsv" > "$tmp/green.out"; rc=$?
[ "$rc" -eq 0 ] || { echo "FAIL: all nine PASS must close the project (rc=$rc)"; fail=1; }

# four target types green, the Kafka-topic one red -> the project does not close
all_pass | sed 's/^\(AllTargetTypesJourneyContainerTest#kafkaTopicTargetJourney\)\tPASS\t/\1\tFAIL\tno body/' > "$tmp/one-red.tsv"
bash "$gate" verdict "$tmp/one-red.tsv" > "$tmp/one-red.out"; rc=$?
[ "$rc" -eq 1 ] || { echo "FAIL: one red target type must block the close (rc=$rc)"; fail=1; }
grep -q 'kafkaTopicTargetJourney' "$tmp/one-red.out" || { echo "FAIL: the red scenario is not named"; fail=1; }

# a BLOCKED scenario is red too, and a scenario with no line at all is NOT_RUN
all_pass | grep -v 'dbTargetJourney' | sed 's/^\(AllTargetTypesJourneyContainerTest#fileTargetJourney\)\tPASS\t/\1\tBLOCKED\tBLOCKED-BY e16/' > "$tmp/missing.tsv"
bash "$gate" verdict "$tmp/missing.tsv" > "$tmp/missing.out"; rc=$?
[ "$rc" -eq 1 ] || { echo "FAIL: BLOCKED and NOT_RUN must block the close (rc=$rc)"; fail=1; }
grep -q 'NOT_RUN.*dbTargetJourney' "$tmp/missing.out" || { echo "FAIL: the missing scenario is not reported NOT_RUN"; fail=1; }

[ "$fail" -eq 0 ] && echo "PASS: the gate closes only on a full green (3 cases)"
exit "$fail"
