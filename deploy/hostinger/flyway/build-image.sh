#!/bin/sh
# Builds the Flyway migration image from a CLEAN, COMMITTED tree (never the working tree) and labels it with the commit.
#   sh deploy/hostinger/flyway/build-image.sh <tag> [image-name]      run inside the git checkout, at the commit being released
#   <tag>        the release tag = the first characters of the full HEAD sha (IMAGE_TAG; at least 7)
#   image-name   default ${IPASS_IMAGE_PREFIX:-acsnewacr.azurecr.io/ipass}/flyway-migrate
# Refuses (exit 2) when: <tag> is not the start of the HEAD commit, a migration folder or flyway/ has uncommitted or untracked
# changes, the guard (check-migration-dirs.sh) fails. The context is `git archive HEAD` of the migration folders and flyway/ only,
# so an uncommitted file cannot enter the image. Labels: org.opencontainers.image.revision=<full sha>,
# ipass.flyway.manifest=<sha256 over the sorted migration blob ids and names>. Prints the image id: record it in the release note
# and pass FLYWAY_IMAGE_ID=<id> to deploy.sh to pin the exact build. deploy.sh refuses an image whose revision label is not <tag>.
set -eu
TAG="${1:-}"; [ -n "$TAG" ] || { echo "Usage: $0 <tag> [image-name]" >&2; exit 2; }
ROOT="$(git rev-parse --show-toplevel)"; cd "$ROOT"
SHA="$(git rev-parse HEAD)"
case "$TAG" in *[!0-9a-f]*|????|???|??|?|??????) echo "ERROR: tag '$TAG' must be at least 7 lowercase hex characters (the start of the commit sha)" >&2; exit 2 ;; esac
case "$SHA" in "$TAG"*) ;; *) echo "ERROR: tag $TAG is not the start of HEAD ($SHA): check out the commit you are releasing" >&2; exit 2 ;; esac
PATHS="$(git ls-tree -r --name-only HEAD | grep -E '(^deploy/hostinger/flyway/|/src/main/resources/db/migration/)')"
DIRTY="$(git status --porcelain --untracked-files=all -- deploy/hostinger/flyway ':(glob)**/src/main/resources/db/migration/**')"
[ -z "$DIRTY" ] || { echo "ERROR: uncommitted or untracked changes in the migration folders or flyway/ - commit or remove them, the image must match the commit:" >&2; echo "$DIRTY" >&2; exit 2; }
CTX="$(mktemp -d)"; trap 'rm -rf "$CTX"' EXIT
trap 'exit 130' INT TERM
# shellcheck disable=SC2086
git archive HEAD $PATHS | tar -x -C "$CTX"
sh "$CTX/deploy/hostinger/flyway/check-migration-dirs.sh" "$CTX" "$CTX/deploy/hostinger/flyway/Dockerfile" || { echo "ERROR: the migration-folder guard failed" >&2; exit 2; }
MANIFEST="$(git ls-tree -r HEAD | grep '/db/migration/' | awk '{print $3 "  " $4}' | sort -k2 | sha256sum | cut -c1-64)"
IMAGE="${2:-${IPASS_IMAGE_PREFIX:-acsnewacr.azurecr.io/ipass}/flyway-migrate}:$TAG"
"${DOCKER:-docker}" build -q -f "$CTX/deploy/hostinger/flyway/Dockerfile" --label "org.opencontainers.image.revision=$SHA" --label "ipass.flyway.manifest=$MANIFEST" -t "$IMAGE" "$CTX" >/dev/null
echo "built $IMAGE revision=$SHA manifest=$MANIFEST"
echo "image id: $("${DOCKER:-docker}" image inspect --format '{{.Id}}' "$IMAGE")"
