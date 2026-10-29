# Flyway Migration Pipeline — Hostinger VPS Deployment

> **Author:** Vasu Ritesh (Manish.Pujara@kalelogistics.com)  
> **Date:** 2026-10-27  
> **Status:** PLAN — not yet implemented  
> **Target:** PostgreSQL 17 on Hostinger VPS (187.127.134.129:5432/ipass_demo)

---

## 1. Current State

### 1.1 How Flyway Runs Today

The Integration Platform uses **Spring Boot 4.1.1** with `spring-boot-starter-flyway` (Flyway 11.x, managed by Boot). Flyway runs **inside the application at startup** — every time `platform-app` starts, it connects to PostgreSQL as `ipass_migrator` (the schema owner) and applies any pending migrations before the app begins serving traffic.

**Spring Boot Flyway config:**
- `platform-tenancy/src/main/resources/config/tenancy-database.yml`:
  ```yaml
  spring:
    flyway:
      user: ${DB_MIGRATION_USERNAME}      # ipass_migrator
      password: ${DB_MIGRATION_PASSWORD}
  ```
- `platform-app/src/main/resources/config/flyway-lock-timeout.yml`:
  ```yaml
  spring:
    flyway:
      init-sqls: SET lock_timeout = '${IPAAS_FLYWAY_LOCK_TIMEOUT:5s}'
  ```
- The datasource (`spring.datasource.*`) in `application.yml` uses a restricted user `ipass_app` (member of `ipaas_app` role).
- Flyway uses the separate `ipass_migrator` login, which has `LOGIN CREATEROLE` and is the `OWNER` of the database.

### 1.2 Migration File Locations (4 modules)

Each Spring Boot module has its own `db/migration/` directory. Flyway auto-discovers them from the classpath.

| Module | Path | Current Files |
|--------|------|---------------|
| `platform-tenancy` | `platform-tenancy/src/main/resources/db/migration/` | V1_3_1 through V2_4_1 (9 files) |
| `platform-config` | `platform-config/src/main/resources/db/migration/` | V2_2_1 through V2_4_1430 (24 files) |
| `platform-delivery` | `platform-delivery/src/main/resources/db/migration/` | V2_3_1 through V2_4_1400 (9 files) |
| `platform-ingest` | `platform-ingest/src/main/resources/db/migration/` | V2_0_1 through V2_1_2 (5 files) |

**Total: ~47 migration files across 4 modules.**

### 1.3 Database Roles (from `deploy/hostinger/postgres-init/20-platform-roles.sh`)

| Role | Attributes | Purpose |
|------|-----------|---------|
| `ipass_admin` | SUPERUSER (PostgreSQL root) | Bootstrap only, never used by the app |
| `ipass_migrator` | LOGIN, CREATEROLE, DB OWNER | Runs Flyway migrations |
| `ipaas_app` | NOLOGIN | Grants bucket — owns nothing, the migration GRANTs to it |
| `ipass_app` | LOGIN, member of `ipaas_app` | Application runtime connections |
| `ipass_backup` | LOGIN, BYPASSRLS, pg_read_all_data | Nightly `pg_dump` |
| `ipass_restore` | LOGIN, CREATEDB | One-shot restore test |

### 1.4 Production Compose (deploy/hostinger/compose.yaml)

The `platform-app` service receives:
```yaml
DB_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-ipass}
DB_USERNAME: ipass_app
DB_PASSWORD: ${DB_PASSWORD}
DB_MIGRATION_USERNAME: ipass_migrator
DB_MIGRATION_PASSWORD: ${DB_MIGRATION_PASSWORD}
```

Flyway runs at app startup using the migrator credentials, then the app serves traffic using the restricted `ipass_app` credentials.

### 1.5 Build Process (from README.md)

```bash
JAVA_HOME=<jdk25> ./mvnw -B -pl platform-app -am package -DskipTests
(cd ui && npm ci && npx vite build)
docker build -f platform-app/Dockerfile -t <registry>/ipass/platform-app:<sc> .
docker build -f ui/Dockerfile -t <registry>/ipass/ui:<sc> .
docker build -f deploy/hostinger/keycloak/Dockerfile -t <registry>/ipass/keycloak:26.8.0-<sc> .
```

The Dockerfile (`platform-app/Dockerfile`) builds a layered JAR image from `eclipse-temurin:25-jre-noble`. The migration SQL files are embedded in the JAR (they're classpath resources).

---

## 2. Problem Statement

Currently, migrations are applied **only when the app starts**. This means:
1. You must deploy the app to run migrations — no way to run migrations independently.
2. If a migration fails, the app doesn't start, and you may not see the error clearly.
3. There's no pre-deployment validation of migrations against the production schema.
4. You cannot run a "dry run" or preview of what migrations will execute.
5. Rolling deployments (if ever needed) would have migration races.

### What We Want

A **standalone Flyway CLI pipeline** that:
1. Runs **before** the app is deployed (separate step).
2. Can be triggered manually via SSH or automated via a script.
3. Validates and applies all pending migrations from the 4 module directories.
4. Produces a clear log of what was applied.
5. Can run `flyway info` to preview pending migrations without applying them.
6. Uses the same `ipass_migrator` role that the app uses.

---

## 3. Architecture Decision: Flyway CLI, Not Liquibase

**Decision: Use Flyway CLI** (not Liquibase).

**Rationale:**
- The project already uses Flyway (Spring Boot Flyway auto-config with 47 existing versioned SQL files).
- Switching to Liquibase would require converting all 47 `.sql` files to Liquibase changelog format (XML/YAML/JSON) and replacing the `flyway_schema_history` table with `databasechangelog`.
- Flyway CLI can read the same `V*__*.sql` files that Spring Boot reads.
- The `flyway_schema_history` table is already populated on the server — Flyway CLI works with it directly.

---

## 4. Implementation Plan

### Step 1: Create the Flyway CLI Docker Image

Create a lightweight Docker image that contains the Flyway CLI and all migration SQL files from the 4 modules.

**File: `deploy/hostinger/flyway/Dockerfile`**
```dockerfile
FROM flyway/flyway:11-alpine

# Copy all migration files from the 4 modules into a single /flyway/sql directory.
# Flyway reads them by version number, not by directory, so merging is safe as long
# as version numbers are globally unique (they are — the project convention is V<major>_<minor>_<seq>).
COPY platform-tenancy/src/main/resources/db/migration/  /flyway/sql/
COPY platform-config/src/main/resources/db/migration/   /flyway/sql/
COPY platform-delivery/src/main/resources/db/migration/  /flyway/sql/
COPY platform-ingest/src/main/resources/db/migration/    /flyway/sql/
```

**Build command** (run from repo root):
```bash
docker build -f deploy/hostinger/flyway/Dockerfile -t ipass/flyway-migrate:<sc> .
```

Where `<sc>` is the short commit hash of the merged `main` branch (same tag convention as the other images).

### Step 2: Create the Flyway Configuration File

**File: `deploy/hostinger/flyway/flyway.toml`**

This is a **template** — actual credentials are injected via environment variables at runtime, never baked into the image.

```toml
[flyway]
# The JDBC URL, username and password are passed as environment variables:
#   FLYWAY_URL      = jdbc:postgresql://<host>:5432/<db>
#   FLYWAY_USER     = ipass_migrator
#   FLYWAY_PASSWORD = <from .env>
#
# All migration SQL files are already in /flyway/sql (COPY'd in the Dockerfile).
locations = ["filesystem:/flyway/sql"]

# Must match the existing Spring Boot Flyway configuration.
# Spring Boot uses the default schema (public) and the default table (flyway_schema_history).
table = "flyway_schema_history"
defaultSchema = "public"

# Match the lock_timeout from flyway-lock-timeout.yml.
initSql = "SET lock_timeout = '5s'"

# Validate that applied migration checksums match the files we have.
# This catches accidental edits to already-applied migration files.
validateOnMigrate = true

# Fail if there are migrations in the history that are missing from the files.
# (Someone deleted a migration file — that's an error.)
ignoreMigrationPatterns = []
```

**IMPORTANT:** This file does NOT go into the Docker image. The Flyway CLI image's default conf location (`/flyway/conf/flyway.toml`) is used. Copy it in the Dockerfile:

Update the Dockerfile to:
```dockerfile
FROM flyway/flyway:11-alpine

COPY deploy/hostinger/flyway/flyway.toml /flyway/conf/flyway.toml
COPY platform-tenancy/src/main/resources/db/migration/  /flyway/sql/
COPY platform-config/src/main/resources/db/migration/   /flyway/sql/
COPY platform-delivery/src/main/resources/db/migration/  /flyway/sql/
COPY platform-ingest/src/main/resources/db/migration/    /flyway/sql/
```

### Step 3: Create the Migration Runner Script

**File: `deploy/hostinger/flyway/migrate.sh`**

This is the script you SSH into the Hostinger VPS and run. It reads credentials from the same `.env` file that compose.yaml uses.

```bash
#!/bin/sh
# Flyway migration runner for iPass on the Hostinger VPS.
# Usage (from /docker/ipass):
#   ./flyway/migrate.sh info       # show pending/applied migrations (dry run)
#   ./flyway/migrate.sh validate   # check checksums match, no apply
#   ./flyway/migrate.sh migrate    # apply all pending migrations
#   ./flyway/migrate.sh repair     # fix failed migrations in history table
#
# Reads credentials from the same .env that compose.yaml uses.
# The Flyway Docker image must be available locally (pulled or built).
set -eu

ACTION="${1:-info}"
case "$ACTION" in
  info|validate|migrate|repair) ;;
  *) echo "Usage: $0 {info|validate|migrate|repair}" >&2; exit 1 ;;
esac

# The .env file lives at /docker/ipass/.env (root-only, mode 600).
# Source it to get the credentials (same file compose.yaml reads).
ENV_FILE="${ENV_FILE:-./.env}"
if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: $ENV_FILE not found. Run bootstrap-env.sh first." >&2
  exit 2
fi

# Parse the .env file (handle export prefix, quotes, comments).
eval "$(grep -E '^(export )?[A-Z_]+=.' "$ENV_FILE" | sed 's/^export //')"

: "${DB_MIGRATION_PASSWORD:?DB_MIGRATION_PASSWORD not set in $ENV_FILE}"
: "${POSTGRES_DB:=ipass}"

# The IMAGE_TAG from .env tells us which flyway image to use.
: "${IMAGE_TAG:?IMAGE_TAG not set in $ENV_FILE}"
FLYWAY_IMAGE="${FLYWAY_IMAGE:-ipass/flyway-migrate:${IMAGE_TAG}}"

echo "=== Flyway $ACTION ==="
echo "Image:    $FLYWAY_IMAGE"
echo "Database: ${POSTGRES_DB}"
echo "User:     ipass_migrator"
echo "Action:   $ACTION"
echo ""

# Run the Flyway CLI container on the same Docker network as PostgreSQL.
# The postgres service is on the ipass_ipass-data network (compose name: ipass, network: ipass-data).
docker run --rm \
  --network ipass_ipass-data \
  -e FLYWAY_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB}" \
  -e FLYWAY_USER="ipass_migrator" \
  -e FLYWAY_PASSWORD="${DB_MIGRATION_PASSWORD}" \
  "$FLYWAY_IMAGE" \
  "$ACTION"

echo ""
echo "=== Flyway $ACTION completed ==="
```

Make it executable:
```bash
chmod +x deploy/hostinger/flyway/migrate.sh
```

### Step 4: Create the Release/Deploy Script

**File: `deploy/hostinger/deploy.sh`**

This is the **full release pipeline** script that:
1. Backs up the database.
2. Runs Flyway `info` (shows what will be applied).
3. Runs Flyway `validate` (checks existing migrations match).
4. Runs Flyway `migrate` (applies pending migrations).
5. Pulls and deploys the new app images.
6. Verifies health.

```bash
#!/bin/sh
# iPass release pipeline for the Hostinger VPS.
# Usage (from /docker/ipass, as root):
#   IMAGE_TAG=<new-commit> ./deploy.sh
#
# Steps:
#   1. Pre-flight checks (disk, .env, images)
#   2. Backup current database
#   3. Flyway: info → validate → migrate
#   4. Update IMAGE_TAG in .env
#   5. Pull new images and recreate services
#   6. Health check
#   7. Post-deploy Flyway info (verify)
#
# To roll back: restore the backup .env and the database dump, then docker compose up -d.
set -eu

# --- Configuration ---
COMPOSE_DIR="/docker/ipass"
ENV_FILE="$COMPOSE_DIR/.env"
BACKUP_DIR="$COMPOSE_DIR/release-backups"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

# --- Pre-flight ---
echo "=== iPass Release Pipeline ==="
echo "Timestamp: $TIMESTAMP"

: "${IMAGE_TAG:?IMAGE_TAG must be set (short commit hash of merged main)}"
echo "Target IMAGE_TAG: $IMAGE_TAG"

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: $ENV_FILE not found." >&2; exit 2
fi

cd "$COMPOSE_DIR"

# Parse current .env
eval "$(grep -E '^(export )?[A-Z_]+=.' "$ENV_FILE" | sed 's/^export //')"
OLD_TAG="${IMAGE_TAG:-unknown}"
echo "Current IMAGE_TAG: $OLD_TAG"
echo ""

# --- Step 1: Backup ---
echo "=== Step 1: Backup ==="
mkdir -p "$BACKUP_DIR/$TIMESTAMP"
cp -p "$ENV_FILE" "$BACKUP_DIR/$TIMESTAMP/.env.bak"
cp -p compose.yaml "$BACKUP_DIR/$TIMESTAMP/compose.yaml.bak"

echo "Running database backup..."
docker compose exec -T pg-backup env RUN_ONCE=1 sh /scripts/backup.sh
if [ $? -ne 0 ]; then
  echo "ERROR: Database backup failed. Aborting release." >&2
  exit 3
fi
echo "Backup complete."
echo ""

# --- Step 2: Build/Pull Flyway Image ---
echo "=== Step 2: Flyway Image ==="
FLYWAY_IMAGE="ipass/flyway-migrate:${IMAGE_TAG}"
echo "Checking for Flyway image: $FLYWAY_IMAGE"

# If the image isn't available locally, it must be built on the build PC
# and pushed/transferred first. The Flyway image is NOT in the registry
# by default — see "Build Flyway Image" section in this plan.
if ! docker image inspect "$FLYWAY_IMAGE" >/dev/null 2>&1; then
  echo "ERROR: Flyway image $FLYWAY_IMAGE not found locally."
  echo "Build it on the build PC and transfer it:"
  echo "  docker build -f deploy/hostinger/flyway/Dockerfile -t $FLYWAY_IMAGE ."
  echo "  docker save $FLYWAY_IMAGE | ssh root@<vps> docker load"
  exit 4
fi
echo "Flyway image available."
echo ""

# --- Step 3: Flyway Info (preview) ---
echo "=== Step 3: Flyway Info (preview pending migrations) ==="
docker run --rm \
  --network ipass_ipass-data \
  -e FLYWAY_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB:-ipass}" \
  -e FLYWAY_USER="ipass_migrator" \
  -e FLYWAY_PASSWORD="${DB_MIGRATION_PASSWORD}" \
  "$FLYWAY_IMAGE" \
  info
echo ""

# --- Step 4: Flyway Validate ---
echo "=== Step 4: Flyway Validate ==="
docker run --rm \
  --network ipass_ipass-data \
  -e FLYWAY_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB:-ipass}" \
  -e FLYWAY_USER="ipass_migrator" \
  -e FLYWAY_PASSWORD="${DB_MIGRATION_PASSWORD}" \
  "$FLYWAY_IMAGE" \
  validate
echo "Validation passed."
echo ""

# --- Step 5: Flyway Migrate ---
echo "=== Step 5: Flyway Migrate ==="
docker run --rm \
  --network ipass_ipass-data \
  -e FLYWAY_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB:-ipass}" \
  -e FLYWAY_USER="ipass_migrator" \
  -e FLYWAY_PASSWORD="${DB_MIGRATION_PASSWORD}" \
  "$FLYWAY_IMAGE" \
  migrate

if [ $? -ne 0 ]; then
  echo ""
  echo "!!! MIGRATION FAILED !!!"
  echo "The database may be partially migrated. Do NOT deploy the new app version."
  echo "To roll back:"
  echo "  1. Restore the database from the backup in $BACKUP_DIR/$TIMESTAMP/"
  echo "  2. Restore .env: cp $BACKUP_DIR/$TIMESTAMP/.env.bak $ENV_FILE"
  echo "  3. docker compose up -d"
  exit 5
fi
echo "Migration complete."
echo ""

# --- Step 6: Update IMAGE_TAG and Deploy ---
echo "=== Step 6: Deploy New App Version ==="
# Update IMAGE_TAG in the .env file
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=${IMAGE_TAG}/" "$ENV_FILE"
echo "Updated IMAGE_TAG to $IMAGE_TAG in .env"

# Determine compose files (include demo overlay if it was already in use)
COMPOSE_FILES="-f compose.yaml"
if docker compose config --services 2>/dev/null | grep -q wiremock; then
  COMPOSE_FILES="$COMPOSE_FILES -f demo/compose.demo.yaml"
  echo "Demo overlay detected, including it."
fi

echo "Pulling new images..."
docker compose $COMPOSE_FILES pull
echo "Recreating services..."
docker compose $COMPOSE_FILES up -d
echo ""

# --- Step 7: Health Check ---
echo "=== Step 7: Health Check ==="
echo "Waiting 90s for services to become healthy..."
sleep 90

HEALTHY=true
for svc in postgres keycloak platform-app edge ui; do
  STATUS="$(docker compose ps --format '{{.Health}}' "$svc" 2>/dev/null || echo "unknown")"
  echo "  $svc: $STATUS"
  case "$STATUS" in
    *healthy*) ;;
    *) HEALTHY=false ;;
  esac
done

if [ "$HEALTHY" = false ]; then
  echo ""
  echo "WARNING: Not all services are healthy. Check: docker compose logs <service>"
  echo "To roll back:"
  echo "  cp $BACKUP_DIR/$TIMESTAMP/.env.bak $ENV_FILE"
  echo "  cp $BACKUP_DIR/$TIMESTAMP/compose.yaml.bak compose.yaml"
  echo "  docker compose up -d"
else
  echo ""
  echo "All services healthy."
fi
echo ""

# --- Step 8: Post-Deploy Flyway Info ---
echo "=== Step 8: Post-Deploy Flyway Info (verify) ==="
docker run --rm \
  --network ipass_ipass-data \
  -e FLYWAY_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB:-ipass}" \
  -e FLYWAY_USER="ipass_migrator" \
  -e FLYWAY_PASSWORD="${DB_MIGRATION_PASSWORD}" \
  "$FLYWAY_IMAGE" \
  info
echo ""

echo "=== Release Complete ==="
echo "Tag: $IMAGE_TAG"
echo "Backup: $BACKUP_DIR/$TIMESTAMP/"
echo ""
echo "Post-deploy checklist:"
echo "  1. Verify app at https://ipass.\${IPASS_DOMAIN}/"
echo "  2. Check logs: docker compose logs -f platform-app --since 5m"
echo "  3. Anonymous GET /api/v1/... returns 401"
echo "  4. Auth at https://auth.\${IPASS_DOMAIN}/ works"
```

### Step 5: Update the Build Pipeline

Add the Flyway image build to the existing build process. The build step goes on the **build PC** (where Maven and Docker are available).

**Updated build sequence:**
```bash
# 1. Build the Java app (existing)
JAVA_HOME=<jdk25> ./mvnw -B -pl platform-app -am package -DskipTests

# 2. Build the UI (existing)
(cd ui && npm ci && npx vite build)

# 3. Build Docker images (existing)
docker build -f platform-app/Dockerfile -t <registry>/ipass/platform-app:<sc> .
docker build -f ui/Dockerfile -t <registry>/ipass/ui:<sc> .
docker build -f deploy/hostinger/keycloak/Dockerfile -t <registry>/ipass/keycloak:26.8.0-<sc> .

# 4. Build Flyway migration image (NEW)
docker build -f deploy/hostinger/flyway/Dockerfile -t ipass/flyway-migrate:<sc> .

# 5. Transfer to VPS (existing pattern + new flyway image)
docker save ipass/flyway-migrate:<sc> | ssh root@<vps-ip> docker load
# (or push to registry and pull on the VPS)
```

### Step 6: Disable App-Startup Migrations (OPTIONAL — Phase 2)

Once the pipeline is proven, you can optionally disable Flyway's auto-run at app startup so that **only** the pipeline applies migrations. This prevents accidental schema changes from a bare `docker compose up -d`.

Add this to the `platform-app` environment in `deploy/hostinger/compose.yaml`:
```yaml
SPRING_FLYWAY_ENABLED: "false"
```

**WARNING:** Only do this AFTER the pipeline is proven and you've run at least 3 successful releases through it. Keep the app-startup Flyway enabled for local dev (`compose/compose.yaml`) — it's convenient for development.

---

## 5. File Tree (new files to create)

```
deploy/hostinger/
├── flyway/
│   ├── Dockerfile          ← Flyway CLI + all 4 module migration dirs
│   ├── flyway.toml         ← Flyway config (locations, lock_timeout, validate)
│   └── migrate.sh          ← Standalone migration runner (info/validate/migrate/repair)
├── deploy.sh               ← Full release pipeline (backup → migrate → deploy → verify)
└── (existing files unchanged)
```

---

## 6. Complete File Contents

### 6.1 `deploy/hostinger/flyway/Dockerfile`

```dockerfile
# Flyway CLI migration image for iPass.
# Build from the repo root:
#   docker build -f deploy/hostinger/flyway/Dockerfile -t ipass/flyway-migrate:<sc> .
# Run:
#   docker run --rm --network ipass_ipass-data \
#     -e FLYWAY_URL=jdbc:postgresql://postgres:5432/ipass \
#     -e FLYWAY_USER=ipass_migrator \
#     -e FLYWAY_PASSWORD=<secret> \
#     ipass/flyway-migrate:<sc> info|validate|migrate|repair
FROM flyway/flyway:11-alpine

# Flyway config
COPY deploy/hostinger/flyway/flyway.toml /flyway/conf/flyway.toml

# Merge all 4 module migration directories into /flyway/sql.
# Version numbers are globally unique across modules, so this is safe.
COPY platform-tenancy/src/main/resources/db/migration/   /flyway/sql/
COPY platform-config/src/main/resources/db/migration/    /flyway/sql/
COPY platform-delivery/src/main/resources/db/migration/  /flyway/sql/
COPY platform-ingest/src/main/resources/db/migration/    /flyway/sql/
```

### 6.2 `deploy/hostinger/flyway/flyway.toml`

```toml
[flyway]
locations = ["filesystem:/flyway/sql"]
table = "flyway_schema_history"
defaultSchema = "public"
initSql = "SET lock_timeout = '5s'"
validateOnMigrate = true
```

### 6.3 `deploy/hostinger/flyway/migrate.sh`

```bash
#!/bin/sh
# Standalone Flyway migration runner for iPass on the Hostinger VPS.
# Usage (from /docker/ipass):
#   ./flyway/migrate.sh info       # show pending/applied migrations
#   ./flyway/migrate.sh validate   # check checksums, no apply
#   ./flyway/migrate.sh migrate    # apply pending migrations
#   ./flyway/migrate.sh repair     # fix history table
set -eu

ACTION="${1:-info}"
case "$ACTION" in
  info|validate|migrate|repair) ;;
  *) echo "Usage: $0 {info|validate|migrate|repair}" >&2; exit 1 ;;
esac

ENV_FILE="${ENV_FILE:-./.env}"
if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: $ENV_FILE not found." >&2; exit 2
fi

# Source .env (handles export prefix and quotes)
eval "$(grep -E '^(export )?[A-Z_]+=.' "$ENV_FILE" | sed 's/^export //')"

: "${DB_MIGRATION_PASSWORD:?DB_MIGRATION_PASSWORD not in $ENV_FILE}"
: "${POSTGRES_DB:=ipass}"
: "${IMAGE_TAG:?IMAGE_TAG not in $ENV_FILE}"

FLYWAY_IMAGE="${FLYWAY_IMAGE:-ipass/flyway-migrate:${IMAGE_TAG}}"

echo "=== Flyway $ACTION ==="
echo "Image:    $FLYWAY_IMAGE"
echo "Database: $POSTGRES_DB"
echo "User:     ipass_migrator"
echo ""

docker run --rm \
  --network ipass_ipass-data \
  -e FLYWAY_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB}" \
  -e FLYWAY_USER="ipass_migrator" \
  -e FLYWAY_PASSWORD="${DB_MIGRATION_PASSWORD}" \
  "$FLYWAY_IMAGE" \
  "$ACTION"

echo ""
echo "=== Flyway $ACTION done ==="
```

### 6.4 `deploy/hostinger/deploy.sh`

(Full content is in Step 4 above — copy that entire script.)

---

## 7. Release Workflow (Step-by-Step)

### 7.1 On the Build PC (developer machine)

```bash
# 1. Ensure you're on main with the latest code
cd D:\Galaxy\integration-platform
git checkout main
git pull

# 2. Get the short commit hash
$SC = (git rev-parse --short HEAD)
echo "Short commit: $SC"

# 3. Build the Java app
$env:JAVA_HOME = "<path-to-jdk25>"
.\mvnw.cmd -B -pl platform-app -am package -DskipTests

# 4. Build the UI
cd ui; npm ci; npx vite build; cd ..

# 5. Build all Docker images (including the new Flyway one)
docker build -f platform-app/Dockerfile -t ipass/platform-app:$SC .
docker build -f ui/Dockerfile -t ipass/ui:$SC .
docker build -f deploy/hostinger/keycloak/Dockerfile -t ipass/keycloak:26.8.0-$SC .
docker build -f deploy/hostinger/flyway/Dockerfile -t ipass/flyway-migrate:$SC .

# 6. Transfer images to VPS (option A: docker save/load over SSH)
docker save ipass/platform-app:$SC ipass/ui:$SC ipass/keycloak:26.8.0-$SC ipass/flyway-migrate:$SC | ssh root@187.127.134.129 docker load

# 6. Transfer images to VPS (option B: push to registry, pull on VPS)
# docker tag ipass/platform-app:$SC <registry>/ipass/platform-app:$SC
# docker push <registry>/ipass/platform-app:$SC
# ... repeat for each image ...
# Then on VPS: docker pull <registry>/ipass/platform-app:$SC
```

### 7.2 On the Hostinger VPS (over SSH)

```bash
# 1. SSH into the VPS
ssh root@187.127.134.129

# 2. Navigate to the project
cd /docker/ipass

# 3. Copy the new flyway/ directory and deploy.sh (if not already there)
# (from the build PC, scp the new files)
# scp -r deploy/hostinger/flyway/ root@187.127.134.129:/docker/ipass/flyway/
# scp deploy/hostinger/deploy.sh root@187.127.134.129:/docker/ipass/deploy.sh
chmod +x flyway/migrate.sh deploy.sh

# 4. Preview pending migrations (safe, read-only)
./flyway/migrate.sh info

# 5. Validate existing migrations (safe, read-only)
./flyway/migrate.sh validate

# 6. Run the full release (backup → migrate → deploy → verify)
IMAGE_TAG=<sc> ./deploy.sh

# OR, to run migrations separately from the deploy:
# a. Apply migrations only:
./flyway/migrate.sh migrate

# b. Then update IMAGE_TAG and deploy:
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=<sc>/" .env
docker compose pull && docker compose up -d
```

---

## 8. Rollback Procedure

### 8.1 Migration Failed (app NOT deployed yet)

If `flyway migrate` fails mid-way:
1. Check the error: `./flyway/migrate.sh info` (shows which migration failed).
2. Fix the migration SQL file, rebuild the Flyway image, and re-run.
3. If the migration is half-applied: `./flyway/migrate.sh repair` (removes failed entries from `flyway_schema_history`), then re-run.
4. If the database is in a bad state: restore from the backup:
   ```bash
   # Stop the app to prevent writes
   docker compose stop platform-app
   # Restore from the pre-release backup
   docker compose exec -T postgres psql -U ipass_admin -d postgres -c "DROP DATABASE ipass;"
   docker compose exec -T postgres psql -U ipass_admin -d postgres -c "CREATE DATABASE ipass OWNER ipass_migrator;"
   # Use the restore test approach, adapted:
   cat /path/to/backup.sql.gz | docker compose exec -T postgres gunzip | psql -U ipass_admin -d ipass
   # Restart
   docker compose up -d
   ```

### 8.2 App Deployed but Broken

1. Restore `.env` from the release backup:
   ```bash
   cp release-backups/<timestamp>/.env.bak .env
   ```
2. Restore the database from the pre-release dump.
3. `docker compose up -d` (rolls back to the old image tag).

---

## 9. Security Considerations

1. **Credentials are NEVER baked into the Flyway Docker image.** They're passed as environment variables at runtime via `docker run -e`.
2. **The `.env` file is root-only (mode 600)** — only root can read it.
3. **The Flyway container runs on the internal `ipass_ipass-data` network** — it reaches PostgreSQL the same way the app does, through Docker's internal DNS. No port is exposed to the internet.
4. **The `ipass_migrator` role has LOGIN + CREATEROLE + DB OWNER** — this is the minimum privilege needed for Flyway to create/alter tables, create roles, and manage the schema. It is NOT a superuser.
5. **The `flyway.toml` contains no secrets** — only configuration.
6. **Migration SQL files should be reviewed before merge** — a malicious migration could drop tables or escalate privileges. Code review is the gate.

---

## 10. Testing Plan

### 10.1 Local Test (before deploying to Hostinger)

```bash
# On your development machine, with local Docker running:
cd D:\Galaxy\integration-platform

# Build the Flyway image
docker build -f deploy/hostinger/flyway/Dockerfile -t ipass/flyway-migrate:local .

# Run info against your local dev database
docker run --rm \
  --network integration-platform_default \
  -e FLYWAY_URL=jdbc:postgresql://postgres:5432/ipaas \
  -e FLYWAY_USER=ipaas \
  -e FLYWAY_PASSWORD=ipaasLocal2026 \
  ipass/flyway-migrate:local \
  info
```

Verify:
- All migrations show as "Applied" or "Pending" (no "Failed" or "Missing").
- Version numbers match the 47 files in the 4 module directories.
- Checksums match (validate passes).

### 10.2 Hostinger Preview Test

```bash
# SSH to VPS
ssh root@187.127.134.129
cd /docker/ipass

# Run info only (safe, no changes)
./flyway/migrate.sh info

# Expected output:
# All existing migrations: "Success" (already applied by the app at startup).
# New migrations (V2_4_1430 etc.): "Pending".
```

### 10.3 Hostinger Migrate Test

```bash
# Run validate first
./flyway/migrate.sh validate

# If validate passes, run migrate
./flyway/migrate.sh migrate

# Verify: run info again
./flyway/migrate.sh info
# All migrations should now show "Success".
```

---

## 11. Network Topology

```
┌─────────────────────────────────────────────────┐
│  Hostinger VPS (187.127.134.129)                │
│                                                  │
│  ┌──────────────────────────────────────────┐   │
│  │  Docker: ipass_ipass-data network        │   │
│  │                                          │   │
│  │  ┌──────────┐     ┌──────────────────┐  │   │
│  │  │ postgres │◄────│ platform-app     │  │   │
│  │  │ :5432    │     │ (Flyway at       │  │   │
│  │  │          │     │  startup)        │  │   │
│  │  │          │     └──────────────────┘  │   │
│  │  │          │                           │   │
│  │  │          │◄────┌──────────────────┐  │   │
│  │  │          │     │ flyway-migrate   │  │   │
│  │  │          │     │ (one-shot CLI    │  │   │
│  │  └──────────┘     │  container)      │  │   │
│  │                   └──────────────────┘  │   │
│  └──────────────────────────────────────────┘   │
│                                                  │
│  Port 5432: NOT exposed to the internet          │
│  Access: SSH only (port 22)                      │
└─────────────────────────────────────────────────┘
```

The Flyway CLI container is a **one-shot container** (`docker run --rm`) that joins the same internal network as PostgreSQL, runs the migration, and is automatically removed.

---

## 12. FAQ

**Q: Why not just use `docker compose exec platform-app` to run Flyway?**  
A: Because the app JAR doesn't include the Flyway CLI. The app uses Spring Boot's Flyway auto-config, which only runs at startup. A standalone Flyway CLI image gives you `info`, `validate`, `repair`, and `migrate` as separate commands without restarting the app.

**Q: Can migrations run from my local machine directly to the Hostinger DB?**  
A: The PostgreSQL port (5432) is NOT exposed to the internet — it's on an internal Docker network. All migration must run from inside the VPS (via SSH + Docker). This is intentional for security.

**Q: What if the app starts and tries to run Flyway at the same time as the pipeline?**  
A: Flyway uses `pg_try_advisory_lock` to prevent concurrent migration runs. The second runner will wait (or fail fast with the lock_timeout). In practice, run migrations BEFORE deploying the app.

**Q: Do I need to change any existing migration files?**  
A: No. The Flyway CLI reads the exact same `V*__*.sql` files that Spring Boot reads. No conversion needed.

**Q: What Flyway version does the CLI image use?**  
A: The `flyway/flyway:11-alpine` image uses Flyway 11.x. Spring Boot 4.1.1 manages Flyway 11.x via its starter. They're the same major version — fully compatible.

---

## 13. Checklist for Implementation

- [ ] Create `deploy/hostinger/flyway/` directory
- [ ] Create `deploy/hostinger/flyway/Dockerfile` (Section 6.1)
- [ ] Create `deploy/hostinger/flyway/flyway.toml` (Section 6.2)
- [ ] Create `deploy/hostinger/flyway/migrate.sh` (Section 6.3)
- [ ] Create `deploy/hostinger/deploy.sh` (Section 6.4 / Step 4)
- [ ] Make scripts executable: `chmod +x deploy/hostinger/flyway/migrate.sh deploy/hostinger/deploy.sh`
- [ ] Build the Flyway Docker image locally and test against local dev DB
- [ ] Run `flyway info` against local DB — verify all 47 migrations listed
- [ ] Run `flyway validate` against local DB — verify checksums match
- [ ] Transfer Flyway image to Hostinger VPS
- [ ] Copy `flyway/` directory and `deploy.sh` to `/docker/ipass/` on VPS
- [ ] Run `./flyway/migrate.sh info` on VPS — verify against production DB
- [ ] Run `./flyway/migrate.sh validate` on VPS — verify checksums
- [ ] Run `./flyway/migrate.sh migrate` on VPS — apply V2_4_1430 (pending)
- [ ] Verify app still works after migration
- [ ] Update README.md with migration pipeline docs
- [ ] (Phase 2, optional) Set `SPRING_FLYWAY_ENABLED=false` in production compose

---

## 14. External Database Access Note

The Hostinger PostgreSQL is **not accessible from outside the VPS**:
- Host: 187.127.134.129, Port: 5432, Database: ipass_demo
- This port is behind Docker's internal network and the VPS firewall (TCP 22/80/443 only)
- All database operations must go through SSH → Docker exec/run on the VPS
- If you need direct SQL access for debugging: `ssh root@187.127.134.129` then `docker compose exec postgres psql -U ipass_migrator -d ipass`
