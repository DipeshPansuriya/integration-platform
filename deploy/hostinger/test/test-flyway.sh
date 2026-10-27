#!/bin/bash
# Offline tests for the Flyway CLI pipeline (deploy/hostinger/flyway/*, deploy/hostinger/deploy.sh): the migration-folder guard,
# migrate.sh and deploy.sh run against a fake docker. No container is started, no lock is needed, nothing touches a database.
# Run (any directory):  bash deploy/hostinger/test/test-flyway.sh      (the container-level proof is README "Migration pipeline")
# Kept apart from test-scripts.sh (its own suite; one Flyway concern per file).
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@"
set +o posix
set -u
for t in awk sed grep mktemp find od; do command -v "$t" >/dev/null 2>&1 || { echo "MISSING PREREQUISITE: $t" >&2; exit 2; }; done
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
fail=0
ok() { echo "PASS $1"; }
bad() { echo "FAIL $1"; fail=1; }
check() { if (eval "$2"); then ok "$1"; else bad "$1"; fi; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
# --- Flyway CLI pipeline (flyway/, deploy.sh): the migration-folder guard, migrate.sh and deploy.sh with a fake docker (no container)
FW="$HERE/flyway"
mk_tree() { # root: three modules, a Dockerfile that copies all three
  r="$1"; rm -rf "$r"; mkdir -p "$r/mod-a/src/main/resources/db/migration" "$r/mod-b/src/main/resources/db/migration" "$r/mod-c/src/main/resources/db/migration" "$r/mod-c/src/test/resources/db/migration" "$r/mod-a/target/classes/db/migration"
  echo 'select 1;' > "$r/mod-a/src/main/resources/db/migration/V1_0_1__a.sql"
  echo 'select 2;' > "$r/mod-b/src/main/resources/db/migration/V1_0_2__b.sql"
  echo 'select 3;' > "$r/mod-c/src/main/resources/db/migration/V1_0_3__c.sql"
  echo 'select 9;' > "$r/mod-c/src/test/resources/db/migration/V1_0_3__test_only.sql"
  echo 'select 9;' > "$r/mod-a/target/classes/db/migration/V1_0_1__built_copy.sql"
  printf 'FROM scratch\nCOPY flyway.toml /flyway/conf/flyway.toml\nCOPY mod-a/src/main/resources/db/migration/ /flyway/sql/\nCOPY mod-b/src/main/resources/db/migration/ /flyway/sql/\nCOPY mod-c/src/main/resources/db/migration/ /flyway/sql/\n' > "$r/Dockerfile"
}
G="$FW/check-migration-dirs.sh"
mk_tree "$TMP/g1"
check "guard: every db/migration folder copied, no duplicates -> OK (test and target folders ignored)" 'sh "$G" "$TMP/g1" "$TMP/g1/Dockerfile" | grep -q "OK: 3 folders, 3 migrations"'
mk_tree "$TMP/g2"; mkdir -p "$TMP/g2/mod-d/src/main/resources/db/migration"; echo 'select 4;' > "$TMP/g2/mod-d/src/main/resources/db/migration/V1_0_4__d.sql"
check "guard: a folder the Dockerfile does not COPY (a future 7th module) fails and names it" '! sh "$G" "$TMP/g2" "$TMP/g2/Dockerfile" >"$TMP/g2.out" 2>&1 && grep -q "mod-d/src/main/resources/db/migration" "$TMP/g2.out"'
mk_tree "$TMP/g3"; echo 'select 5;' > "$TMP/g3/mod-b/src/main/resources/db/migration/V1_0_1__other_name.sql"
check "guard: the same version in two folders fails" '! sh "$G" "$TMP/g3" "$TMP/g3/Dockerfile" >"$TMP/g3.out" 2>&1 && grep -qi "duplicate version" "$TMP/g3.out"'
mk_tree "$TMP/g4"; echo 'select 5;' > "$TMP/g4/mod-b/src/main/resources/db/migration/V1_0_1__a.sql"
check "guard: the same file name in two folders fails" '! sh "$G" "$TMP/g4" "$TMP/g4/Dockerfile" >/dev/null 2>&1'
mk_tree "$TMP/g5"; echo 'select 5;' > "$TMP/g5/mod-b/src/main/resources/db/migration/V1.0.01__same_as_1_0_1.sql"
check "guard: versions Flyway treats as equal (V1_0_1 / V1.0.01) are duplicates" '! sh "$G" "$TMP/g5" "$TMP/g5/Dockerfile" >"$TMP/g5.out" 2>&1 && grep -qi "duplicate version" "$TMP/g5.out"'
mk_tree "$TMP/g6"; echo 'COPY mod-z/src/main/resources/db/migration/ /flyway/sql/' >> "$TMP/g6/Dockerfile"
mk_tree "$TMP/g7"; mkdir -p "$TMP/g7/mod-a/src/main/resources/db/migration/sub"; echo 'select 7;' > "$TMP/g7/mod-a/src/main/resources/db/migration/sub/V1_0_7__sub.sql"
check "guard: a sub-folder inside a migration folder (the app would load it, the image would drop it) fails" '! sh "$G" "$TMP/g7" "$TMP/g7/Dockerfile" >"$TMP/g7.out" 2>&1 && grep -q "sub" "$TMP/g7.out"'
mk_tree "$TMP/g8"; echo 'x' > "$TMP/g8/mod-b/src/main/resources/db/migration/V1_0_8__other.java"
check "guard: a file that is not V*.sql / R*.sql in a migration folder fails" '! sh "$G" "$TMP/g8" "$TMP/g8/Dockerfile" >/dev/null 2>&1'
mk_tree "$TMP/g9"; sed -i 's#^COPY mod-a#COPY --chown=1:1 mod-a#' "$TMP/g9/Dockerfile"
check "guard: COPY with flags (--chown=...) still counts as a copy" 'sh "$G" "$TMP/g9" "$TMP/g9/Dockerfile" >/dev/null 2>&1'
check "guard: a Dockerfile COPY of a folder that does not exist fails" '! sh "$G" "$TMP/g6" "$TMP/g6/Dockerfile" >/dev/null 2>&1'
nfiles=$(find "$REPO" \( -name node_modules -o -name target -o -name .git \) -prune -o -type d -path '*/src/main/resources/db/migration' -print | while read -r d; do ls "$d"; done | wc -l | tr -d ' ')
check "guard: the real tree - every db/migration folder is copied, no duplicate version (count taken from the files: $nfiles)" 'sh "$G" "$REPO" "$FW/Dockerfile" | grep -qE "^OK: [0-9]+ folders, $nfiles migrations"'

# migrate.sh with a fake docker that logs its arguments and the password it saw in its environment
cat > "$TMP/fakedocker" <<'FAKE'
#!/bin/sh
case "$1 $2" in "image inspect") img=""; for a in "$@"; do img="$a"; done; case "$*" in *--format*) t="${img##*:}"; case "$*" in *revision*) echo "${FAKE_REVISION-${t}0123456789abcdef}";; *) echo "${FAKE_IMAGE_ID:-sha256:fakeid}";; esac; exit 0;; esac;; esac
echo "$*" >> "$FAKE_LOG"
echo "pw=${FLYWAY_PASSWORD:-unset}" >> "$FAKE_ENVLOG"
for a in "$@"; do case "$a" in *:/run/flyway-secret:ro)
  d="${a%:/run/flyway-secret:ro}"; dd="$(cygpath -u "$d" 2>/dev/null || echo "$d")"
  echo "secretfile=$(sed -n 's/^password = "\(.*\)"$/\1/p' "$dd/secret.toml") fmode=$(umask 077; stat -c %a "$dd/secret.toml") dmode=$(umask 077; stat -c %a "$dd")" >> "$FAKE_ENVLOG" ;; esac; done
act=""; for a in "$@"; do case "$a" in info|validate|migrate|repair) act="$a";; esac; done
[ -n "$act" ] && [ "$act" = "${FAKE_FAIL_ACTION:-}" ] && { echo "fake flyway: $act failed" >&2; exit 1; }
exit 0
FAKE
chmod +x "$TMP/fakedocker"
printf 'POSTGRES_DB=ipass_x\nIMAGE_TAG=oldtag1\nDB_MIGRATION_PASSWORD="p@ss_word"\r\nHOSTILE=$(touch %s/PWNED3)\nIPASS_IMAGE_PREFIX=reg.example/ipass\nOTHER=`touch %s/PWNED2`\n' "$TMP" "$TMP" "$TMP" > "$TMP/m.env"
mkdir -p "$TMP/fwtmp"
modeof() { (umask 077; stat -c %a "$1"); }      # Git Bash derives file modes from the umask; Linux shows the real mode
mig() { : > "$TMP/fake.log"; : > "$TMP/fake.env"; TMPDIR="$TMP/fwtmp" FAKE_LOG="$TMP/fake.log" FAKE_ENVLOG="$TMP/fake.env" DOCKER="$TMP/fakedocker" ENV_FILE="$TMP/m.env" sh "$FW/migrate.sh" "$@"; }
mig info >/dev/null 2>&1
check "migrate: info runs exactly one container, action info, on the .env tag and prefix" '[ "$(wc -l < "$TMP/fake.log")" -eq 1 ] && grep -q "reg.example/ipass/flyway-migrate:oldtag1" "$TMP/fake.log" && grep -q " info\$" "$TMP/fake.log"'
check "migrate: the .env is parsed, never executed (no eval of \$( ) or backticks)" '[ ! -e "$TMP/PWNED3" ] && [ ! -e "$TMP/PWNED2" ]'
check "migrate: the password reaches Flyway only through a mounted config file (quotes and CRLF stripped), never the command line or the container environment" 'grep -qF "secretfile=p@ss_word" "$TMP/fake.env" && grep -qF "pw=unset" "$TMP/fake.env" && ! grep -qF "p@ss" "$TMP/fake.log" && ! grep -q "FLYWAY_PASSWORD" "$TMP/fake.log" && grep -q -- "-configFiles=/run/flyway-secret/secret.toml" "$TMP/fake.log"'
check "migrate: the secret file is mode 600 in a mode 700 directory, mounted read-only, and gone after the run" 'grep -q "fmode=600 dmode=700" "$TMP/fake.env" && [ -z "$(ls "$TMP/fwtmp")" ]'
check "migrate: the container is hardened (read-only root with a tmpfs /tmp, cap-drop ALL, no-new-privileges, non-root user)" 'grep -q -- "--read-only" "$TMP/fake.log" && grep -q -- "--tmpfs /tmp" "$TMP/fake.log" && grep -q -- "--cap-drop ALL" "$TMP/fake.log" && grep -q -- "--security-opt no-new-privileges:true" "$TMP/fake.log" && grep -qE -- "--user [0-9]+:[0-9]+" "$TMP/fake.log" && ! grep -qE -- "--user (0|root)" "$TMP/fake.log"'
FAKE_FAIL_ACTION=migrate mig migrate >/dev/null 2>&1
check "migrate: the secret file is removed also when Flyway fails" '[ -z "$(ls "$TMP/fwtmp")" ]'
printf 'POSTGRES_DB=ipass
IMAGE_TAG=t1
DB_MIGRATION_PASSWORD=a"b\c
' > "$TMP/m4.env"
: > "$TMP/fake.env"; TMPDIR="$TMP/fwtmp" FAKE_LOG="$TMP/fake.log" FAKE_ENVLOG="$TMP/fake.env" DOCKER="$TMP/fakedocker" ENV_FILE="$TMP/m4.env" sh "$FW/migrate.sh" info >/dev/null 2>&1
check "migrate: a password with a double quote and a backslash is escaped in the TOML file" 'grep -qF "secretfile=a\\\"b\\\\c" "$TMP/fake.env"'
check "migrate: user is ipass_migrator, database from .env, container on the data network" 'grep -q "FLYWAY_USER=ipass_migrator" "$TMP/fake.log" && grep -q "jdbc:postgresql://postgres:5432/ipass_x" "$TMP/fake.log" && grep -q -- "--network ipass_ipass-data" "$TMP/fake.log"'
IMAGE_TAG=newtag2 mig info >/dev/null 2>&1
check "migrate: a caller-supplied IMAGE_TAG wins over the .env (compose precedence)" 'grep -q "flyway-migrate:newtag2" "$TMP/fake.log" && ! grep -q oldtag1 "$TMP/fake.log"'
mig validate >/dev/null 2>&1
check "migrate: validate is a single read-only action" '[ "$(wc -l < "$TMP/fake.log")" -eq 1 ] && grep -q " validate\$" "$TMP/fake.log"'
mig migrate >/dev/null 2>&1
check "migrate: migrate runs validate first, then migrate" '[ "$(wc -l < "$TMP/fake.log")" -eq 2 ] && sed -n 1p "$TMP/fake.log" | grep -q " validate\$" && sed -n 2p "$TMP/fake.log" | grep -q " migrate\$"'
FAKE_FAIL_ACTION=validate mig migrate >/dev/null 2>&1; rc=$?
check "migrate: a failing validate stops migrate (exit non-zero, migrate never invoked)" '[ $rc -ne 0 ] && [ "$(wc -l < "$TMP/fake.log")" -eq 1 ] && ! grep -q " migrate\$" "$TMP/fake.log"'
FAKE_FAIL_ACTION=migrate mig migrate >"$TMP/mf.out" 2>&1; rc=$?
check "migrate: a failing migrate exits non-zero and says so" '[ $rc -ne 0 ] && grep -q "MIGRATION FAILED" "$TMP/mf.out"'
mig repair >/dev/null 2>&1; rc=$?
check "migrate: repair without --yes is refused, nothing runs" '[ $rc -ne 0 ] && [ ! -s "$TMP/fake.log" ]'
mig repair --yes >/dev/null 2>&1
check "migrate: repair --yes runs repair" 'grep -q " repair\$" "$TMP/fake.log"'
mig bogus >/dev/null 2>&1; rc=$?
check "migrate: an unknown action exits 1" '[ $rc -eq 1 ]'
printf 'POSTGRES_DB=ipass\nIMAGE_TAG=t1\n' > "$TMP/m2.env"
FAKE_LOG="$TMP/fake.log" FAKE_ENVLOG="$TMP/fake.env" DOCKER="$TMP/fakedocker" ENV_FILE="$TMP/m2.env" sh "$FW/migrate.sh" info >/dev/null 2>&1; rc=$?
check "migrate: no DB_MIGRATION_PASSWORD in the env file exits 2" '[ $rc -eq 2 ]'
check "flyway: no password in Dockerfile, flyway.toml or the scripts, and no eval" '! grep -qiE "password *=|FLYWAY_PASSWORD=|ipaasLocal" "$FW/Dockerfile" "$FW/flyway.toml" && ! grep -qE "(^|[;&| ])eval " "$FW/migrate.sh" "$FW/flyway-run.sh" "$HERE/deploy.sh" "$FW/check-migration-dirs.sh"'
check "flyway: image is pinned to the Spring-Boot-managed Flyway 12.4.0, by digest" 'grep -qE "^FROM flyway/flyway:12\.4\.0-alpine@sha256:[0-9a-f]{64}" "$FW/Dockerfile"'
check "flyway: config matches the app (table, validateOnMigrate, lock_timeout 5s init-sql, same default as flyway-lock-timeout.yml)" 'grep -q "flyway_schema_history" "$FW/flyway.toml" && grep -q "validateOnMigrate" "$FW/flyway.toml" && grep -q "SET lock_timeout = .5s." "$FW/flyway.toml" && grep -q "IPAAS_FLYWAY_LOCK_TIMEOUT:5s" "$REPO/platform-app/src/main/resources/config/flyway-lock-timeout.yml"'
for f in "$FW/migrate.sh" "$FW/flyway-run.sh" "$HERE/deploy.sh" "$FW/check-migration-dirs.sh"; do
  check "flyway: $(basename "$f") is POSIX sh syntax-clean, LF only, no BOM" 'sh -n "$f" && ! grep -q "$(printf "\r")" "$f" && [ "$(head -c3 "$f" | od -An -tx1 | tr -d " ")" != efbbbf ]'
done

# deploy.sh with a fake docker: order of the steps, the tag it records, failure exits
cat > "$TMP/fakedocker2" <<'FAKE'
#!/bin/sh
echo "$*" >> "$FAKE_LOG"
echo "tag=${IMAGE_TAG:-unset} pw=${FLYWAY_PASSWORD:-unset}" >> "$FAKE_ENVLOG"
case "$1" in pull) [ "${FAKE_NO_IMAGE:-0}" = 1 ] && exit 1; exit 0;; esac
case "$1 $2" in "image inspect") img=""; for a in "$@"; do img="$a"; done; case "$*" in *--format*) t="${img##*:}"; case "$*" in *revision*) echo "${FAKE_REVISION-${t}0123456789abcdef}";; *) echo "${FAKE_IMAGE_ID:-sha256:fakeid}";; esac; exit 0;; esac;; esac
case "$1 $2" in
  "compose up") [ "${FAKE_FAIL_UP:-0}" = 1 ] && exit 1; exit 0;;
  "compose pull") [ "${FAKE_FAIL_PULL:-0}" = 1 ] && exit 1; exit 0;;
  "image inspect") [ "${FAKE_NO_IMAGE:-0}" = 1 ] && exit 1; exit 0;;
  "compose exec") [ "${FAKE_FAIL_BACKUP:-0}" = 1 ] && exit 1; exit 0;;
  "compose ps") echo "cid123"; exit 0;;
  "inspect --format") echo "${FAKE_HEALTH:-healthy}"; exit 0;;
esac
act=""; for a in "$@"; do case "$a" in info|validate|migrate) act="$a";; esac; done
[ -n "$act" ] && [ "$act" = "${FAKE_FAIL_ACTION:-}" ] && exit 1
exit 0
FAKE
chmod +x "$TMP/fakedocker2"
dep() { rm -rf "$TMP/stack"; mkdir -p "$TMP/stack/flyway"; cp "$HERE/deploy.sh" "$TMP/stack/deploy.sh"; cp "$FW/migrate.sh" "$FW/flyway-run.sh" "$TMP/stack/flyway/"; : > "$TMP/stack/compose.yaml"
  printf 'POSTGRES_DB=ipass\nIMAGE_TAG=oldtag1\nDB_MIGRATION_PASSWORD=s3cr3tvalue\n' > "$TMP/stack/.env"
  : > "$TMP/fake.log"; : > "$TMP/fake.env"
  FAKE_LOG="$TMP/fake.log" FAKE_ENVLOG="$TMP/fake.env" DOCKER="$TMP/fakedocker2" HEALTH_TIMEOUT=2 HEALTH_INTERVAL=1 "$@" sh "$TMP/stack/deploy.sh"; }
dep env IMAGE_TAG=newtag2 >"$TMP/d1.out" 2>&1; rc=$?
check "deploy: happy path exits 0, .env now carries the new tag, the old tag is recorded and backed up" '[ $rc -eq 0 ] && grep -q "^IMAGE_TAG=newtag2\$" "$TMP/stack/.env" && grep -q "oldtag1" "$TMP/d1.out" && grep -q "^IMAGE_TAG=oldtag1\$" "$TMP/stack/release-backups"/*/.env.bak'
check "deploy: order is backup, then info, validate, migrate, only then compose up" 'b=$(grep -n "compose exec" "$TMP/fake.log" | head -1 | cut -d: -f1); i=$(grep -n " info\$" "$TMP/fake.log" | head -1 | cut -d: -f1); v=$(grep -n " validate\$" "$TMP/fake.log" | head -1 | cut -d: -f1); m=$(grep -n " migrate\$" "$TMP/fake.log" | head -1 | cut -d: -f1); u=$(grep -n "compose up" "$TMP/fake.log" | head -1 | cut -d: -f1); [ -n "$b" ] && [ "$b" -lt "$i" ] && [ "$i" -lt "$v" ] && [ "$v" -lt "$m" ] && [ "$m" -lt "$u" ]'
check "deploy: the Flyway container uses the NEW tag (the caller IMAGE_TAG is not overwritten by .env)" 'grep -q "flyway-migrate:newtag2" "$TMP/fake.log" && ! grep -q "flyway-migrate:oldtag1" "$TMP/fake.log"'
check "deploy: the password is in no command line and no output; .env keeps its other lines" '! grep -q s3cr3tvalue "$TMP/fake.log" "$TMP/d1.out" && grep -q "^DB_MIGRATION_PASSWORD=s3cr3tvalue\$" "$TMP/stack/.env"'
dep env IMAGE_TAG=newtag2 FAKE_FAIL_BACKUP=1 >"$TMP/d2.out" 2>&1; rc=$?
check "deploy: a failed backup aborts before any migration or compose up (exit 3)" '[ $rc -eq 3 ] && ! grep -q "flyway-migrate" "$TMP/fake.log" && ! grep -q "compose up" "$TMP/fake.log" && grep -q "^IMAGE_TAG=oldtag1\$" "$TMP/stack/.env"'
dep env IMAGE_TAG=newtag2 FAKE_FAIL_ACTION=migrate >"$TMP/d3.out" 2>&1; rc=$?
check "deploy: a failed migrate prints MIGRATION FAILED, exits 5, never touches the app or the tag" '[ $rc -eq 5 ] && grep -q "MIGRATION FAILED" "$TMP/d3.out" && ! grep -q "compose up" "$TMP/fake.log" && grep -q "^IMAGE_TAG=oldtag1\$" "$TMP/stack/.env"'
dep env IMAGE_TAG=newtag2 FAKE_FAIL_ACTION=validate >"$TMP/d4.out" 2>&1; rc=$?
check "deploy: a failed validate stops the release (non-zero), no migrate, no compose up" '[ $rc -ne 0 ] && ! grep -q " migrate\$" "$TMP/fake.log" && ! grep -q "compose up" "$TMP/fake.log"'
dep env IMAGE_TAG=newtag2 FAKE_HEALTH=starting >"$TMP/d5.out" 2>&1; rc=$?
check "deploy: a service that never becomes healthy makes the script exit non-zero (6) with the rollback steps" '[ $rc -eq 6 ] && grep -qi "not healthy" "$TMP/d5.out" && grep -q "oldtag1" "$TMP/d5.out"'
dep env IMAGE_TAG=newtag2 FAKE_NO_IMAGE=1 >"$TMP/d6.out" 2>&1; rc=$?
check "deploy: a missing Flyway image stops before anything changes (exit 4)" '[ $rc -eq 4 ] && ! grep -q "compose up" "$TMP/fake.log" && grep -q "^IMAGE_TAG=oldtag1\$" "$TMP/stack/.env"'
dep env -u IMAGE_TAG >"$TMP/d7.out" 2>&1; rc=$?
check "deploy: no IMAGE_TAG exits 2" '[ $rc -eq 2 ]'
check "deploy: a tag of latest, or with odd characters, is refused" '! dep env IMAGE_TAG=latest >/dev/null 2>&1 && ! dep env "IMAGE_TAG=a b;rm" >/dev/null 2>&1'

# --- review round (Varun B1, C1, C5, C6, C8)
mig validate >/dev/null 2>&1
check "migrate: validate keeps Flyway's default *:future next to *:pending (a DB ahead of the image is not a validate failure)" 'grep -q -- "-ignoreMigrationPatterns=\*:pending,\*:future" "$TMP/fake.log"'
mig info >/dev/null 2>&1
check "migrate: the init SQL is the app's default lock_timeout 5s" 'grep -qF -- "-initSql=SET lock_timeout = '"'"'5s'"'"'" "$TMP/fake.log"'
IPAAS_FLYWAY_LOCK_TIMEOUT=9s mig info >/dev/null 2>&1
check "migrate: IPAAS_FLYWAY_LOCK_TIMEOUT (the app's variable) is honoured" 'grep -qF -- "-initSql=SET lock_timeout = '"'"'9s'"'"'" "$TMP/fake.log"'
IPAAS_FLYWAY_LOCK_TIMEOUT="5s'; drop table x; --" mig info >/dev/null 2>&1; rc=$?
check "migrate: a lock timeout that is not a plain Postgres duration is refused (exit 2), nothing runs" '[ $rc -eq 2 ] && [ ! -s "$TMP/fake.log" ]'
printf 'POSTGRES_DB=ipass\nIMAGE_TAG=t1\nDB_MIGRATION_PASSWORD=ab$cd\n' > "$TMP/m3.env"
FAKE_LOG="$TMP/fake.log" FAKE_ENVLOG="$TMP/fake.env" DOCKER="$TMP/fakedocker" ENV_FILE="$TMP/m3.env" sh "$FW/migrate.sh" info >"$TMP/m3.out" 2>&1; rc=$?
check "migrate: a password with \$ (compose would interpolate it, this script would not) is refused with a reason, value not printed" '[ $rc -eq 2 ] && grep -qi "compose" "$TMP/m3.out" && ! grep -q "ab.cd" "$TMP/m3.out"'
dep env IMAGE_TAG=newtag2 FAKE_FAIL_UP=1 >"$TMP/d8.out" 2>&1; rc=$?
check "deploy: a failing 'compose up' exits 6, prints the rollback note, and puts the old tag back in the env file" '[ $rc -eq 6 ] && grep -q "oldtag1" "$TMP/d8.out" && grep -qi "restored" "$TMP/d8.out" && grep -q "^IMAGE_TAG=oldtag1\$" "$TMP/stack/.env"'
dep env IMAGE_TAG=newtag2 FAKE_FAIL_PULL=1 >"$TMP/d9.out" 2>&1; rc=$?
check "deploy: images that exist only locally (docker save | load) work: no explicit compose pull, so a failing pull cannot stop the release" '[ $rc -eq 0 ] && ! grep -q "compose pull" "$TMP/fake.log" && grep -q "^IMAGE_TAG=newtag2\$" "$TMP/stack/.env"'
dep_export() { rm -rf "$TMP/stack"; mkdir -p "$TMP/stack/flyway"; cp "$HERE/deploy.sh" "$TMP/stack/deploy.sh"; cp "$FW/migrate.sh" "$FW/flyway-run.sh" "$TMP/stack/flyway/"; : > "$TMP/stack/compose.yaml"
  printf 'POSTGRES_DB=ipass\nexport IMAGE_TAG="oldtag1"\nDB_MIGRATION_PASSWORD=s3cr3tvalue\n' > "$TMP/stack/.env"; : > "$TMP/fake.log"; : > "$TMP/fake.env"
  FAKE_LOG="$TMP/fake.log" FAKE_ENVLOG="$TMP/fake.env" DOCKER="$TMP/fakedocker2" HEALTH_TIMEOUT=2 HEALTH_INTERVAL=1 IMAGE_TAG=newtag2 sh "$TMP/stack/deploy.sh"; }
dep_export >"$TMP/d10.out" 2>&1; rc=$?
check "deploy: an 'export IMAGE_TAG=\"...\"' line is read as the old tag and rewritten in place (one IMAGE_TAG line, no duplicate)" '[ $rc -eq 0 ] && grep -q "oldtag1 -> newtag2" "$TMP/d10.out" && [ "$(grep -c "IMAGE_TAG=" "$TMP/stack/.env")" -eq 1 ] && grep -q "^export IMAGE_TAG=newtag2\$" "$TMP/stack/.env"'

# --- row 538 hardening: release-backups permissions and pruning
mkbk() { mkdir -p "$TMP/stack/release-backups"; for n in 1 2 3 4 5 6 7; do mkdir -p "$TMP/stack/release-backups/2020010${n}T000000Z"; echo old > "$TMP/stack/release-backups/2020010${n}T000000Z/.env.bak"; done; mkdir -p "$TMP/stack/release-backups/keepme-notes"; }
dep_pre() { rm -rf "$TMP/stack"; mkdir -p "$TMP/stack/flyway"; cp "$HERE/deploy.sh" "$TMP/stack/deploy.sh"; cp "$FW/migrate.sh" "$FW/flyway-run.sh" "$TMP/stack/flyway/"; : > "$TMP/stack/compose.yaml"; chmod 644 "$TMP/stack/compose.yaml" "$TMP/stack/deploy.sh"
  printf 'POSTGRES_DB=ipass
IMAGE_TAG=oldtag1
DB_MIGRATION_PASSWORD=s3cr3tvalue
' > "$TMP/stack/.env"; chmod 644 "$TMP/stack/.env"; mkbk
  : > "$TMP/fake.log"; : > "$TMP/fake.env"
  FAKE_LOG="$TMP/fake.log" FAKE_ENVLOG="$TMP/fake.env" DOCKER="$TMP/fakedocker2" HEALTH_TIMEOUT=2 HEALTH_INTERVAL=1 TMPDIR="$TMP/fwtmp" IMAGE_TAG=newtag2 "$@" sh "$TMP/stack/deploy.sh"; }
dep_pre env >"$TMP/b1.out" 2>&1; rc=$?
cur="$(ls -d "$TMP/stack/release-backups"/2*Z | tail -1)"
check "deploy: release-backups is mode 700, the release dir 700, .env.bak and compose.yaml.bak mode 600 (sources were 644)" '[ $rc -eq 0 ] && [ "$(modeof "$TMP/stack/release-backups")" = 700 ] && [ "$(modeof "$cur")" = 700 ] && [ "$(modeof "$cur/.env.bak")" = 600 ] && [ "$(modeof "$cur/compose.yaml.bak")" = 600 ]'
check "deploy: old release-backups are pruned to the newest 5 (default), the current one is kept, and each pruned one is logged" '[ "$(ls -d "$TMP/stack/release-backups"/2*Z | wc -l)" -eq 5 ] && [ -f "$cur/.env.bak" ] && [ -d "$TMP/stack/release-backups/20200107T000000Z" ] && [ ! -d "$TMP/stack/release-backups/20200101T000000Z" ] && [ "$(grep -c "pruned release-backup" "$TMP/b1.out")" -eq 3 ]'
check "deploy: a folder in release-backups that is not a release timestamp is never touched" '[ -d "$TMP/stack/release-backups/keepme-notes" ]'
dep_pre env RELEASE_BACKUPS_KEEP=1 >"$TMP/b2.out" 2>&1; rc=$?
cur="$(ls -d "$TMP/stack/release-backups"/2*Z | tail -1)"
check "deploy: RELEASE_BACKUPS_KEEP=1 keeps only the current release backup" '[ $rc -eq 0 ] && [ "$(ls -d "$TMP/stack/release-backups"/2*Z | wc -l)" -eq 1 ] && [ -f "$cur/.env.bak" ] && [ "${cur##*/}" != 20200107T000000Z ]'
dep_pre env RELEASE_BACKUPS_KEEP=abc >"$TMP/b3.out" 2>&1; rc=$?
check "deploy: a non-numeric RELEASE_BACKUPS_KEEP is refused (exit 2) before anything changes" '[ $rc -eq 2 ] && ! grep -q "compose" "$TMP/fake.log"'
check "proof: KEEP=1 writes the env copy as mode 600 with a printed path and warning, and every exit path (also failure, INT, TERM) cleans up" 'grep -q "chmod 600" "$HERE/test/flyway-local-proof.sh" && grep -q "WARNING" "$HERE/test/flyway-local-proof.sh" && grep -q "trap .* EXIT" "$HERE/test/flyway-local-proof.sh" && grep -qE "trap .*(INT|TERM)" "$HERE/test/flyway-local-proof.sh"'

# --- row 538 review round 2 (Karan F1 F2 F6 F7 F8 F11): image revision label, run lock, atomic .env, half-recreated stack
FAKE_REVISION=deadbeef0000 mig info >"$TMP/l1.out" 2>&1; rc=$?
check "migrate: an image whose revision label is not the IMAGE_TAG commit is refused (exit 4) before the container or the password is used" '[ $rc -eq 4 ] && grep -q "was built from commit deadbeef0000" "$TMP/l1.out" && ! grep -q "^run " "$TMP/fake.log"'
FAKE_REVISION= mig info >"$TMP/l2.out" 2>&1; rc=$?
check "migrate: an image with no revision label is refused (exit 4)" '[ $rc -eq 4 ] && grep -q "no org.opencontainers.image.revision label" "$TMP/l2.out"'
FLYWAY_IMAGE_ID=sha256:other mig info >"$TMP/l3.out" 2>&1; rc=$?
check "migrate: FLYWAY_IMAGE_ID pins the exact image id (a different id is refused, exit 4)" '[ $rc -eq 4 ] && grep -q "expected sha256:other" "$TMP/l3.out"'
dep env IMAGE_TAG=newtag2 FAKE_REVISION=deadbeef0000 >"$TMP/l4.out" 2>&1; rc=$?
check "deploy: a stale/foreign Flyway image (wrong revision label) stops the release before any migration or tag change" '[ $rc -ne 0 ] && ! grep -q "^run " "$TMP/fake.log" && ! grep -q "compose up" "$TMP/fake.log" && grep -q "^IMAGE_TAG=oldtag1\$" "$TMP/stack/.env"'
printf 'POSTGRES_DB=ipass?x=1
IMAGE_TAG=t1
DB_MIGRATION_PASSWORD=abc
' > "$TMP/m5.env"
FAKE_LOG="$TMP/fake.log" FAKE_ENVLOG="$TMP/fake.env" DOCKER="$TMP/fakedocker" ENV_FILE="$TMP/m5.env" sh "$FW/migrate.sh" info >/dev/null 2>&1; rc=$?
check "migrate: POSTGRES_DB with JDBC-URL characters is refused (exit 2)" '[ $rc -eq 2 ]'
dep_pre env >"$TMP/p1.out" 2>&1; rc=$?
check "deploy: the run lock is released after a successful run, no .env temp file is left, .env keeps its mode" '[ $rc -eq 0 ] && [ ! -e "$TMP/stack/.deploy.lock" ] && [ -z "$(ls -A "$TMP/stack" | grep "^.env.new")" ]'
dep_pre env FAKE_FAIL_ACTION=migrate >/dev/null 2>&1
check "deploy: the run lock is released also when the release fails" '[ ! -e "$TMP/stack/.deploy.lock" ]'
rm -rf "$TMP/stack"; mkdir -p "$TMP/stack/flyway" "$TMP/stack/.deploy.lock"; cp "$HERE/deploy.sh" "$TMP/stack/"; : > "$TMP/stack/.env"; : > "$TMP/stack/compose.yaml"
IMAGE_TAG=newtag2 DOCKER="$TMP/fakedocker2" sh "$TMP/stack/deploy.sh" >"$TMP/p2.out" 2>&1; rc=$?
check "deploy: a second deploy.sh while one runs (lock folder present) is refused (exit 2) and leaves the lock of the first alone" '[ $rc -eq 2 ] && grep -q "another deploy.sh is running" "$TMP/p2.out" && [ -d "$TMP/stack/.deploy.lock" ]'
dep_pre env FAKE_FAIL_UP=1 >"$TMP/p3.out" 2>&1; rc=$?
check "deploy: after a failed 'up -d' the output says some services may already run the new tag, lists compose ps, and warns against a plain up -d" '[ $rc -eq 6 ] && grep -q "SOME services may already run newtag2" "$TMP/p3.out" && grep -q "compose ps" "$TMP/fake.log" && grep -q "Do NOT run another plain" "$TMP/p3.out"'
check "rotate-secret: warns that release-backups still hold the old value" 'grep -q "release-backups/\*/.env.bak copies still hold the OLD" "$HERE/rotate-secret.sh"'
# build-image.sh: clean-tree wrapper with a fake docker, in a throwaway git repo shaped like the real one
cat > "$TMP/fakebuild" <<'FAKE'
#!/bin/sh
echo "$*" >> "$FAKE_LOG"
case "$1 $2" in "image inspect") echo sha256:builtid;; esac
exit 0
FAKE
chmod +x "$TMP/fakebuild"
B="$TMP/brepo"; rm -rf "$B"; mkdir -p "$B/deploy/hostinger/flyway"; cp "$FW"/Dockerfile "$FW"/Dockerfile.dockerignore "$FW"/flyway.toml "$FW"/check-migration-dirs.sh "$B/deploy/hostinger/flyway/"
vn=0; for m in platform-tenancy platform-config platform-delivery platform-ingest platform-app platform-routing; do vn=$((vn + 1)); mkdir -p "$B/$m/src/main/resources/db/migration"; echo "select 1;" > "$B/$m/src/main/resources/db/migration/V1_0_${vn}__$m.sql"; done
echo uncommitted-neighbour > "$B/README.txt"
( cd "$B" && git init -q && git config user.email t@t && git config user.name t && git add -A && git commit -q -m init ) >/dev/null 2>&1
HEADSHA="$(cd "$B" && git rev-parse HEAD)"; SC="$(echo "$HEADSHA" | cut -c1-8)"
bi() { : > "$TMP/fake.log"; ( cd "$B" && FAKE_LOG="$TMP/fake.log" DOCKER="$TMP/fakebuild" sh "$FW/build-image.sh" "$@" ); }
bi "$SC" reg.example/ipass/flyway-migrate >"$TMP/bi1.out" 2>&1; rc=$?
check "build-image: a clean tree at the tagged commit builds, labelled with the full sha, and prints the image id" '[ $rc -eq 0 ] && grep -q "org.opencontainers.image.revision=$HEADSHA" "$TMP/fake.log" && grep -q "ipass.flyway.manifest=" "$TMP/fake.log" && grep -q "image id: sha256:builtid" "$TMP/bi1.out"'
echo "select 2;" > "$B/platform-app/src/main/resources/db/migration/V1_0_99__uncommitted.sql"
bi "$SC" reg.example/ipass/flyway-migrate >"$TMP/bi2.out" 2>&1; rc=$?
check "build-image: an untracked migration file refuses the build (exit 2), no docker build runs" '[ $rc -eq 2 ] && grep -q "V1_0_99__uncommitted.sql" "$TMP/bi2.out" && ! grep -q "^build" "$TMP/fake.log"'
rm -f "$B/platform-app/src/main/resources/db/migration/V1_0_99__uncommitted.sql"; :
bi 1234567 reg.example/ipass/flyway-migrate >"$TMP/bi3.out" 2>&1; rc=$?
check "build-image: a tag that is not the start of HEAD is refused (exit 2)" '[ $rc -eq 2 ] && grep -q "is not the start of HEAD" "$TMP/bi3.out" && ! grep -q "^build" "$TMP/fake.log"'
check "build-image: POSIX sh syntax, no BOM, LF only" 'sh -n "$FW/build-image.sh" && ! od -An -c "$FW/build-image.sh" | grep -q "\\\\r" && [ "$(head -c3 "$FW/build-image.sh" | od -An -tx1 | tr -d " ")" != efbbbf ]'

echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"
exit $fail
