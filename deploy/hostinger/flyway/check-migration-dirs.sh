#!/bin/sh
# Guard for the Flyway CLI image (flyway/Dockerfile). The image is a copy of every module's src/main/resources/db/migration
# folder; the application finds the same files on its classpath. A module that gains such a folder but is not COPY'd
# in the Dockerfile would be migrated by the app and silently MISSED by the pipeline (the CLI would call the database
# up to date while the app still has work to do). This script fails the build in that case, and also when two folders
# carry the same file name or the same Flyway version (the Dockerfile merges all folders into one directory, so a
# duplicate would overwrite another file without a word).
# Usage:  sh deploy/hostinger/flyway/check-migration-dirs.sh [repo-root] [dockerfile]
#         (defaults: the repository this script is in, flyway/Dockerfile next to it)
# Run it before every `docker build` of the Flyway image; deploy/hostinger/test/test-flyway.sh runs it on the real tree.
# It also fails on anything in a migration folder that is not a plain V*.sql / R*.sql file (a sub-folder or another suffix
# would be loaded by the app but dropped from the image). COPY flags (--chown=...) are accepted.
# Exit 0 = OK, 1 = a problem (listed), 2 = bad usage. Reads only; prints paths and versions, never file contents.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${1:-$HERE/../../..}"
DOCKERFILE="${2:-$HERE/Dockerfile}"
[ -d "$ROOT" ] || { echo "check-migration-dirs: no such directory: $ROOT" >&2; exit 2; }
[ -r "$DOCKERFILE" ] || { echo "check-migration-dirs: cannot read $DOCKERFILE" >&2; exit 2; }
ROOT="$(cd "$ROOT" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail=0

# 1. every src/main db/migration folder (build output and test folders are not part of the app's migration set)
find "$ROOT" \( -name node_modules -o -name target -o -name .git \) -prune -o -type d -path '*/src/main/resources/db/migration' -print \
  | sed "s#^$ROOT/##" | sort > "$TMP/dirs"
# 2. every COPY source in the Dockerfile that points at a db/migration folder (trailing slash normalised)
awk '$1 == "COPY" { for (i = 2; i <= NF; i++) if ($i !~ /^--/) { if ($i ~ /db\/migration\/?$/) { sub(/\/$/, "", $i); print $i } break } }' "$DOCKERFILE" | sort > "$TMP/copied"

while IFS= read -r d; do
  grep -Fxq -- "$d" "$TMP/copied" || { echo "FAIL: $d exists but $(basename "$DOCKERFILE") does not COPY it - the pipeline would miss its migrations" >&2; fail=1; }
done < "$TMP/dirs"
while IFS= read -r c; do
  grep -Fxq -- "$c" "$TMP/dirs" || { echo "FAIL: $(basename "$DOCKERFILE") COPYs $c but that folder does not exist" >&2; fail=1; }
done < "$TMP/copied"

# 3. duplicates across the folders that are copied
: > "$TMP/files"
while IFS= read -r d; do
  [ -d "$ROOT/$d" ] || continue
  for f in "$ROOT/$d"/* "$ROOT/$d"/.[!.]*; do
    [ -e "$f" ] || continue
    b="$(basename "$f")"
    if [ -d "$f" ]; then echo "FAIL: $d contains the sub-folder $b - the app would load it, the image would drop it" >&2; fail=1; continue; fi
    case "$b" in
      V*.sql|R*.sql) printf '%s\t%s\n' "$b" "$d" >> "$TMP/files" ;;
      *) echo "FAIL: $d/$b is not a V*.sql or R*.sql file - the app might load it, the image would drop it" >&2; fail=1 ;;
    esac
  done
done < "$TMP/dirs"
cut -f1 "$TMP/files" | sort | uniq -d | while IFS= read -r n; do
  echo "FAIL: duplicate file name $n in: $(awk -F'\t' -v n="$n" '$1 == n { printf "%s ", $2 }' "$TMP/files")" >&2
done
# Flyway's own equality: '_' and '.' separate parts, leading zeros do not count, trailing zero parts do not count
awk -F'\t' '
  $1 ~ /^V[0-9]/ {
    v = $1; sub(/^V/, "", v); sub(/__.*$/, "", v); gsub(/_/, ".", v)
    n = split(v, p, "."); for (i = 1; i <= n; i++) { sub(/^0+/, "", p[i]); if (p[i] == "") p[i] = "0" }
    while (n > 1 && p[n] == "0") n--
    k = p[1]; for (i = 2; i <= n; i++) k = k "." p[i]
    seen[k] = seen[k] " " $2 "/" $1
    cnt[k]++
  }
  END { for (k in cnt) if (cnt[k] > 1) printf "FAIL: duplicate version %s in:%s\n", k, seen[k] > "/dev/stderr" }
' "$TMP/files" 2> "$TMP/dupver" || true
if [ -s "$TMP/dupver" ]; then cat "$TMP/dupver" >&2; fail=1; fi
# the file-name duplicates above ran in a subshell: count them again for the exit status
[ -z "$(cut -f1 "$TMP/files" | sort | uniq -d)" ] || fail=1

if [ "$fail" -ne 0 ]; then exit 1; fi
echo "OK: $(wc -l < "$TMP/dirs" | tr -d ' ') folders, $(wc -l < "$TMP/files" | tr -d ' ') migrations, all copied by $(basename "$DOCKERFILE"), no duplicate name or version"
