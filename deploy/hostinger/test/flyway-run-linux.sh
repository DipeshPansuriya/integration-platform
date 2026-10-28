#!/bin/bash
# Real-Linux check of flyway/flyway-run.sh (password file, modes, owner, chown, cleanup on exit and on SIGTERM, argument list):
# runs test/flyway-run-linux-inner.sh inside a Linux container once as root and once as a non-root uid, with a stub `docker`.
# The container only reads the repo (mounted read-only) and writes to its own /tmp; no network, no Flyway, no database.
#   bash deploy/hostinger/test/flyway-run-linux.sh          (takes a Docker lock slot; reuses the local flyway/flyway image: no pull)
# Image: LINUX_TEST_IMAGE (default flyway/flyway:12.4.0-alpine, which is already on the build PC; needs sh, mktemp, stat, chown).
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@"
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LOCKROOT="${DOCKER_SUITE_LOCKROOT:-/d/Projects/ipass/.docker-suite.lock}"
IMAGE="${LINUX_TEST_IMAGE:-flyway/flyway:12.4.0-alpine}"
LOCK=""
for l in "$LOCKROOT-3" "$LOCKROOT-4" "$LOCKROOT-5" "$LOCKROOT-6"; do if mkdir "$l" 2>/dev/null; then LOCK="$l"; break; fi; done
[ -n "$LOCK" ] || { echo "no free docker lock - not started"; exit 2; }
echo "arjun flyway-run-linux pid=$$ $(date -u +%FT%TZ)" > "$LOCK/owner"
trap 'rm -rf "$LOCK"' EXIT
trap 'exit 130' INT TERM
docker image inspect "$IMAGE" >/dev/null 2>&1 || { echo "image $IMAGE is not on this host (set LINUX_TEST_IMAGE)"; exit 2; }
MNT="$HERE"; command -v cygpath >/dev/null 2>&1 && MNT="$(cygpath -w "$HERE")"
PW="throwaway-$(openssl rand -hex 8)"
rc=0
for u in root 54321:54321; do
  echo "##### container user: $u"
  MSYS_NO_PATHCONV=1 docker run --rm --user "$u" --network none --cap-drop ALL --cap-add CHOWN --cap-add FOWNER --cap-add DAC_OVERRIDE \
    -e H=/h -e PW="$PW" -v "$MNT:/h:ro" --entrypoint sh "$IMAGE" /h/test/flyway-run-linux-inner.sh || rc=1
done
[ "$rc" = 0 ] && echo "LINUX OK (root and non-root)" || echo "LINUX FAILURES"
exit "$rc"
