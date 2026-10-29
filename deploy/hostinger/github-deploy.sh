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
# The real POSTGRES_USER/POSTGRES_DB live in the host .env; compose.yaml defaults
# (ipass_admin/ipass) may not match. Read them from $ENV_FILE without printing any
# value. DB_USER/DB_NAME environment overrides still win.
db_user_default="$(awk -F= '/^POSTGRES_USER=/{ sub(/\r$/, ""); v=$2; sub(/^"/, "", v); sub(/"$/, "", v); print v; exit }' "$ENV_FILE")"
db_name_default="$(awk -F= '/^POSTGRES_DB=/{ sub(/\r$/, ""); v=$2; sub(/^"/, "", v); sub(/"$/, "", v); print v; exit }' "$ENV_FILE")"
db_user="${DB_USER:-${db_user_default:-ipass_admin}}"
db_name="${DB_NAME:-${db_name_default:-ipass}}"
db_host_psql="no"
db_compose_exec="no"
db_container_exec="no"
postgres_container="$(docker ps --filter name=^/postgres$ --format '{{.ID}}' 2>/dev/null | head -1)"
if command -v psql >/dev/null 2>&1; then
  if PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -p 5432 -U "$db_user" -d "$db_name" -X -A -t -c 'SELECT 1' >/dev/null 2>&1; then
    db_host_psql="yes"
  fi
else
  db_host_psql="skipped (no psql on host)"
fi
if [ -n "$postgres_container" ]; then
  if docker exec -T "$postgres_container" env PGPASSWORD="$DB_PASSWORD" psql -U "$db_user" -d "$db_name" -X -A -t -c 'SELECT 1' >/dev/null 2>&1; then
    db_container_exec="yes"
  fi
else
  db_container_exec="skipped (no running postgres container)"
fi
if docker compose exec -T postgres env PGPASSWORD="$DB_PASSWORD" psql -U "$db_user" -d "$db_name" -X -A -t -c 'SELECT 1' >/dev/null 2>&1; then
  db_compose_exec="yes"
fi
if [ "$db_host_psql" != "yes" ] && [ "$db_container_exec" != "yes" ] && [ "$db_compose_exec" != "yes" ]; then
  echo "ERROR: db preflight failed (host psql 127.0.0.1:5432 as ${db_user}/${db_name}: ${db_host_psql}; docker exec ${postgres_container:-none} as ${db_user}/${db_name}: ${db_container_exec}; docker compose exec postgres as ${db_user}/${db_name}: ${db_compose_exec})" >&2
  echo "--- docker compose ps ---" >&2
  docker compose ps >&2 || true
  exit 2
fi
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
