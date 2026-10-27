# Flyway CLI migration image for iPass (standalone pipeline; the app still runs Flyway at its own start-up).
# Same migration files as the app: every module's src/main/resources/db/migration folder, merged into /flyway/sql.
# Build from the repo root, after the guard (fails if a module's folder is missing here or a name/version repeats):
#   sh deploy/hostinger/flyway/check-migration-dirs.sh
#   docker build -f deploy/hostinger/flyway/Dockerfile -t <registry>/ipass/flyway-migrate:<sc> .
# Run it with deploy/hostinger/flyway/migrate.sh (README, "Migration pipeline"). No secret and no connection value is baked
# in: FLYWAY_URL / FLYWAY_USER / FLYWAY_PASSWORD arrive as environment variables at run time.
# The CLI is pinned to the Flyway version Spring Boot 4.1.1 manages for the app (flyway-core 12.4.0), by digest like the
# other base images; re-pin the digest with the Boot upgrade.
FROM flyway/flyway:12.4.0-alpine@sha256:b43c3d9b7227687682a9124451ac3dbc9b0003eca65290ad1dcda760345bc680

COPY deploy/hostinger/flyway/flyway.toml /flyway/conf/flyway.toml

# One COPY per module, the SIX that carry migrations today (59 files). Versions are unique across them (the guard checks
# it), so merging into one folder is safe. A seventh module with a db/migration folder MUST be added here:
# check-migration-dirs.sh fails otherwise.
COPY platform-tenancy/src/main/resources/db/migration/  /flyway/sql/
COPY platform-config/src/main/resources/db/migration/   /flyway/sql/
COPY platform-delivery/src/main/resources/db/migration/ /flyway/sql/
COPY platform-ingest/src/main/resources/db/migration/   /flyway/sql/
COPY platform-app/src/main/resources/db/migration/      /flyway/sql/
COPY platform-routing/src/main/resources/db/migration/  /flyway/sql/
