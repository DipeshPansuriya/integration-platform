#!/usr/bin/env bash
# Provenance check (T-089 fix round 1, E0-F1-S1; T-079, T-081 documents).
# Scenario "A recreated section cites its source": every '## ' section of PRD.md, architecture.md,
#   rule.md and ADR-002, -003, -004, -008, -009 contains, before the next '## ', either
#   (a) a source paragraph - a line starting '*Source:' or '*Sources:' plus its continuation lines up
#       to the next blank line - naming at least one surviving hub source as `ws/<path>:<number>`, or
#   (b) the label "Added in the recreation" or "new decision, not a recreation" (any case).
#   A citation that names only an ADR, a memory id or a person is not a hub file:line and fails.
# Scenario "A section with no surviving source is never presented as a recreation" (@negative):
#   ADR-001 carries "new decision, not a recreation" and is listed for re-confirmation in
#   documents/adr/README.md; a section with neither a source nor the label fails (planted copy);
#   every section labelled "Added in the recreation" is listed for re-confirmation (ADRs in the
#   re-confirmation section of documents/adr/README.md, other files in PRD.md's recreation note).
# Usage: build-checks/check-provenance.sh [file ...]
#   No argument: the in-scope documents above plus the ADR-001 checks. With file arguments (e.g. a
#   planted copy outside the repository) only those files get the per-section check.
#   Needs no Docker and no Maven.
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
docs="$root/documents"

run=0
fail=0
pass() { run=$((run + 1)); echo "PASS: $1"; }
miss() { run=$((run + 1)); fail=$((fail + 1)); echo "FAIL: $1"; }

# Prints one line per '## ' section: "<line>|<ok 0/1>|<heading>".
sections() {
  awk '
    function flush() { if (head != "") printf "%d|%d|%s\n", hline, ok, head }
    /^## / { flush(); head = $0; hline = NR; ok = 0; insrc = 0; next }
    head == "" { next }
    /^\*Sources?:/ { insrc = 1 }
    /^[[:space:]]*$/ { insrc = 0 }
    insrc && /ws\/[^`[:space:]]*:[0-9]/ { ok = 1 }
    tolower($0) ~ /added in the recreation|new decision, not a recreation/ { ok = 1 }
    END { flush() }
  ' "$1"
}

check_file() {
  local f="$1" name="$2"
  if [ ! -f "$f" ]; then
    miss "$name missing"
    return
  fi
  local n=0 line ok head
  while IFS='|' read -r line ok head; do
    n=$((n + 1))
    if [ "$ok" = "1" ]; then
      pass "$name:$line '$head'"
    else
      miss "$name:$line '$head' - no *Source: with a ws/<path>:<line> and no recreation label"
    fi
  done < <(sections "$f")
  [ "$n" -eq 0 ] && miss "$name has no '## ' section"
}

# Prints the heading text (without '## ') of every section that carries "Added in the recreation".
labelled() {
  awk '
    /^## / { head = substr($0, 4); done = 0; next }
    head != "" && !done && tolower($0) ~ /added in the recreation/ { print head; done = 1 }
  ' "$1"
}

# @negative "And it is listed for Dipesh's re-confirmation": a labelled ADR section is listed in the
# re-confirmation section of documents/adr/README.md (one line naming the ADR and the heading); a
# labelled section of PRD.md, architecture.md or rule.md is named (file and heading) in PRD.md's
# recreation note above its first section.
check_listed() {
  local rel="$1" f="$docs/$1" h
  [ -f "$f" ] || return
  while IFS= read -r h; do
    [ -z "$h" ] && continue
    case "$rel" in
      adr/*)
        local id
        id="$(basename "$rel" | cut -c1-7)"
        if awk '/^## / { on = (tolower($0) ~ /re-confirmation/) } on' "$docs/adr/README.md" 2>/dev/null \
            | grep -F -- "$id" | grep -qF -- "$h"; then
          pass "documents/$rel '## $h' (labelled) is listed for re-confirmation in documents/adr/README.md"
        else
          miss "documents/$rel '## $h' carries the recreation label but documents/adr/README.md's re-confirmation section does not list it"
        fi
        ;;
      *)
        local note
        note="$(awk '/^## / { exit } { printf "%s ", $0 }' "$docs/PRD.md" 2>/dev/null)"
        if printf '%s' "$note" | grep -qF -- "$(basename "$rel")" && printf '%s' "$note" | grep -qF -- "$h"; then
          pass "documents/$rel '## $h' (labelled) is named in PRD.md's re-confirmation note"
        else
          miss "documents/$rel '## $h' carries the recreation label but PRD.md's recreation note does not name it"
        fi
        ;;
    esac
  done < <(labelled "$f")
}

if [ "$#" -gt 0 ]; then
  for f in "$@"; do check_file "$f" "$f"; done
else
  for rel in PRD.md architecture.md rule.md adr/ADR-002-datastore.md adr/ADR-003-rules-engine.md \
             adr/ADR-004-mapping-canvas.md adr/ADR-008-camel-adapter-only.md \
             adr/ADR-009-tenant-directory-rls.md; do
    check_file "$docs/$rel" "documents/$rel"
    check_listed "$rel"
  done
  adr1="$docs/adr/ADR-001-modular-monolith.md"
  if grep -qiF "new decision, not a recreation" "$adr1" 2>/dev/null; then
    pass "ADR-001 carries 'new decision, not a recreation'"
  else
    miss "ADR-001 does not carry 'new decision, not a recreation'"
  fi
  # Planted negative: a copy of ADR-009 with its first section's source line stripped must fail.
  planted="$(mktemp "${TMPDIR:-/tmp}/planted-provenance.XXXXXX")"
  awk 'seen == 0 && /^## / { seen = 1 } seen == 1 && /^\*Sources?:/ { seen = 2; next } { print }'     "$docs/adr/ADR-009-tenant-directory-rls.md" > "$planted"
  first="$(sections "$planted" | head -n 1)"
  rm -f "$planted"
  if [ "${first#*|}" != "${first}" ] && [ "$(printf '%s' "$first" | cut -d'|' -f2)" = "0" ]; then
    pass "planted negative: ADR-009 copy with '$(printf '%s' "$first" | cut -d'|' -f3)' stripped of its source fails"
  else
    miss "planted negative: ADR-009 copy stripped of its first source still passes ($first)"
  fi
  if grep -F "ADR-001" "$docs/adr/README.md" 2>/dev/null | grep -qiF "re-confirmation"; then
    pass "ADR-001 is listed for Dipesh's re-confirmation in documents/adr/README.md"
  else
    miss "ADR-001 is not listed for re-confirmation in documents/adr/README.md"
  fi
fi

echo "provenance: run=$run failed-checks=$fail"
[ "$fail" -eq 0 ]
