#!/usr/bin/env bash
# R18 agent-guidance check (T-089, E0-F3-S2).
# Scenario "Guidance points at the recreated documents": AGENTS.md links documents/PRD.md,
#   documents/architecture.md, documents/rule.md and documents/adr/ (relative links that resolve),
#   and names Java 25, Spring Boot, Apache Camel, Kafka and React.
# Scenario "Stale references are refused" (@negative): a case-insensitive fixed-string search for
#   "_docs/", ".NET" and "Angular" gives 0 hits.
# R18: AGENTS.md is the one agent guidance file - no CLAUDE.md and no .claude/ folder in the repository.
# Usage: build-checks/check-agent-guidance.sh [guidance-file]
#   Default file: AGENTS.md at the repository root. A file argument (e.g. a planted copy outside
#   the repository) is checked the same way; its relative links resolve against the repository root,
#   where AGENTS.md lives. Needs no Docker and no Maven.
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
file="${1:-$root/AGENTS.md}"

run=0
fail=0
pass() { run=$((run + 1)); echo "PASS: $1"; }
miss() { run=$((run + 1)); fail=$((fail + 1)); echo "FAIL: $1"; }

run=$((run + 1))
if [ ! -f "$file" ]; then
  fail=$((fail + 1))
  echo "FAIL: guidance file missing - $file"
  echo "agent guidance: run=$run failed-checks=$fail"
  exit 1
fi
echo "PASS: guidance file exists - $file"

# Every Markdown link target, without a title, anchor or leading "./".
links="$(grep -oE '\]\([^)[:space:]]+' "$file" | sed -e 's/^](//' -e 's/#.*$//' -e 's#^\./##')"

for want in documents/PRD.md documents/architecture.md documents/rule.md documents/adr/; do
  bare="${want%/}"
  if printf '%s\n' "$links" | grep -qxF -e "$want" -e "$bare"; then
    if [ -e "$root/$bare" ]; then
      pass "links $want (resolves)"
    else
      miss "links $want but $root/$bare does not exist"
    fi
  else
    miss "no relative link to $want"
  fi
done

# Every other relative link must resolve too (a broken link is a wrong pointer).
while IFS= read -r target; do
  [ -z "$target" ] && continue
  case "$target" in http://*|https://*|mailto:*) continue ;; esac
  if [ ! -e "$root/$target" ]; then
    miss "relative link $target does not resolve"
  fi
done <<< "$links"

for name in "Java 25" "Spring Boot" "Apache Camel" "Kafka" "React"; do
  if grep -qF "$name" "$file"; then
    pass "names $name"
  else
    miss "does not name $name"
  fi
done

for stale in "_docs/" ".NET" "Angular"; do
  hits="$(grep -oiF -- "$stale" "$file" | wc -l | tr -d ' ')"
  if [ "$hits" -eq 0 ]; then
    pass "stale reference \"$stale\": 0 hits"
  else
    miss "stale reference \"$stale\": $hits hit(s): $(grep -niF -- "$stale" "$file" | head -n 3 | tr '\n' ' ')"
  fi
done

if [ -e "$root/CLAUDE.md" ] || [ -e "$root/.claude" ]; then
  miss "R18: CLAUDE.md or .claude/ present at the repository root - AGENTS.md is the one guidance file"
else
  pass "R18: no CLAUDE.md and no .claude/ at the repository root"
fi

echo "agent guidance: run=$run failed-checks=$fail"
[ "$fail" -eq 0 ]
