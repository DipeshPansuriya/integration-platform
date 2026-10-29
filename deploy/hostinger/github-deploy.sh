#!/bin/sh
# Automated GitHub Actions release helper for iPaaS on Hostinger.
# Isolated from deploy.sh so it can be read/tested independently.
# Inputs (environment only, never on argv): IMAGE_TAG, APP_IMAGE, UI_IMAGE, DB_PASSWORD.
# It performs preflight checks, updates exactly two .env lines, then delegates to deploy.sh.
set -eu
umask 077

STACK_DIR="${STACK_DIR:-/docker/ipass}"
cd "$STACK_DIR"
ENV_FILE="${ENV_FILE:-$STACK_DIR/.env}"

: "${IMAGE_TAG:?IMAGE_TAG is required}"
: "${APP_IMAGE:?APP_IMAGE is required}"
: "${UI_IMAGE:?UI_IMAGE is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
[ -r "$ENV_FILE" ] || { echo "ERROR: cannot read $ENV_FILE" >&2; exit 2; }
[ -x "$STACK_DIR/deploy.sh" ] || { echo "ERROR: deploy.sh not found/executable" >&2; exit 2; }

echo "=== preflight: memory ==="
mem_kb="$(awk '/^MemAvailable:/ {print $2; exit}' /proc/meminfo)"
[ -n "$mem_kb" ] || { echo "ERROR: cannot read MemAvailable" >&2; exit 2; }
if [ "$mem_kb" -lt 4194304 ]; then
  echo "ERROR: MemAvailable ${mem_kb} KB < 4194304 KB (4 GB)" >&2
  exit 2
fi
echo "MemAvailable ${mem_kb} KB (>= 4 GB) OK"

echo "=== preflight: database (read-only) ==="
db_check_ok=0
if command -v psql >/dev/null 2>&1; then
  if PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -p 5432 -U "${DB_USER:-ipass}" -d "${DB_NAME:-ipass}" -X -A -t -c 'SELECT 1' >/dev/null 2>&1; then
    db_check_ok=1
  fi
else
  if docker compose exec -T postgres env PGPASSWORD="$DB_PASSWORD" psql -U "${DB_USER:-ipass}" -d "${DB_NAME:-ipass}" -X -A -t -c 'SELECT 1' >/dev/null 2>&1; then
    db_check_ok=1
  fi
fi
[ "$db_check_ok" = 1 ] || { echo "ERROR: read-only database preflight failed" >&2; exit 2; }
echo "database preflight OK"

echo "=== backup ==="
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="$STACK_DIR/release-backups/$STAMP"
mkdir -p "$BACKUP_DIR"
chmod 700 "$STACK_DIR/release-backups" "$BACKUP_DIR"
cp "$ENV_FILE" "$BACKUP_DIR/.env.bak"
chmod 600 "$BACKUP_DIR/.env.bak"
echo "backup written to $BACKUP_DIR/.env.bak"

echo "=== env swap (two lines only) ==="
tmp="$(mktemp "$STACK_DIR/.env.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
awk -v a="$APP_IMAGE" -v u="$UI_IMAGE" '
  /^IPASS_APP_IMAGE=/ { print "IPASS_APP_IMAGE=" a; seen_app=1; next }
  /^IPASS_UI_IMAGE=/  { print "IPASS_UI_IMAGE=" u;  seen_ui=1;  next }
  { print }
  END {
    if (!seen_app) print "IPASS_APP_IMAGE=" a
    if (!seen_ui)  print "IPASS_UI_IMAGE=" u
  }
' "$ENV_FILE" > "$tmp"
chmod 600 "$tmp"
mv "$tmp" "$ENV_FILE"
tmp=""
echo "IPASS_APP_IMAGE and IPASS_UI_IMAGE updated (IMAGE_TAG left for deploy.sh)"

echo "=== deploy via deploy.sh ==="
IMAGE_TAG="$IMAGE_TAG" "$STACK_DIR/deploy.sh"

echo "=== smoke ==="
echo "deploy.sh completed and its health gate passed; release $IMAGE_TAG is live."
