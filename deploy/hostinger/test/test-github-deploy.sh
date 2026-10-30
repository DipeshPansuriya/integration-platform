#!/bin/sh
# Minimal structural/security checks for the GitHub Actions release automation.
set -eu
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
W="$ROOT/.github/workflows/release.yml"
G="$ROOT/deploy/hostinger/github-deploy.sh"
test -f "$W"; test -f "$G"
grep -q 'permissions:' "$W"
grep -q 'contents: read' "$W"
grep -q 'environment: Production Deploy' "$W"
grep -q '4194304' "$G"
grep -q 'DB_PASSWORD' "$G"
! grep -E 'ghp_|AKIA|password:[[:space:]]*[^$]' "$W" >/dev/null
echo "github-deploy structural checks passed"
