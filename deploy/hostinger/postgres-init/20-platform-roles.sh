#!/bin/sh
# Production DB roles for iPass (ADR-H-IPASS-01 Decision 3), idempotent, K1-safe. Runs once on an empty volume and
# may be re-run by hand (it also rotates the passwords):
#   docker compose exec -T postgres sh /docker-entrypoint-initdb.d/20-platform-roles.sh
#   ipass_migrator  LOGIN CREATEROLE, OWNER of the database - the Flyway owner (V1_3_1 needs DB owner + CREATEROLE)
#   ipaas_app       NOLOGIN, exactly V1_3_1's attributes (the migration accepts it when it already exists)
#   ipass_app       LOGIN, member of ipaas_app, owns nothing - the app's DB_USERNAME (DatabaseRoleGuard K1 passes)
#   ipass_backup    LOGIN BYPASSRLS + pg_read_all_data, NOCREATEDB - nightly pg_dump of both databases and the restore
#                   test's row counts only. Not in the ADR: the audit tables FORCE row-level security, so even their
#                   owner dumps zero rows; the bootstrap superuser must not be a service login.
#   ipass_restore   LOGIN CREATEDB, NOBYPASSRLS, no other privilege - creates, fills and drops the scratch database of
#                   the restore test (one-shot container). Kept apart from ipass_backup so no single credential can
#                   both read everything and create databases.
# Passwords arrive as environment variables and go to psql as variables; nothing is written to a file.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v db="$POSTGRES_DB" -v mig_pw="$DB_MIGRATION_PASSWORD" -v app_pw="$DB_PASSWORD" -v bak_pw="$BACKUP_DB_PASSWORD" -v rst_pw="$RESTORE_DB_PASSWORD" <<'SQL'
SELECT 'CREATE ROLE ipass_migrator' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_migrator') \gexec
SELECT format('ALTER ROLE ipass_migrator LOGIN NOSUPERUSER CREATEROLE NOCREATEDB NOBYPASSRLS NOREPLICATION PASSWORD %L', :'mig_pw') \gexec
SELECT 'CREATE ROLE ipaas_app NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION'
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipaas_app') \gexec
SELECT 'CREATE ROLE ipass_app' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_app') \gexec
SELECT format('ALTER ROLE ipass_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION PASSWORD %L', :'app_pw') \gexec
GRANT ipaas_app TO ipass_app;
SELECT 'CREATE ROLE ipass_backup' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_backup') \gexec
SELECT format('ALTER ROLE ipass_backup LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS NOREPLICATION PASSWORD %L', :'bak_pw') \gexec
GRANT pg_read_all_data TO ipass_backup;
SELECT 'CREATE ROLE ipass_restore' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_restore') \gexec
SELECT format('ALTER ROLE ipass_restore LOGIN NOSUPERUSER CREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION PASSWORD %L', :'rst_pw') \gexec
SELECT format('ALTER DATABASE %I OWNER TO ipass_migrator', :'db') \gexec
-- fail loudly if the split is not what K1 needs
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('ipass_app','ipaas_app','ipass_migrator') AND (rolsuper OR rolbypassrls)) THEN
    RAISE EXCEPTION 'K1: ipass_app, ipaas_app and ipass_migrator must be neither superuser nor BYPASSRLS';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_backup' AND (rolsuper OR rolcreatedb))
     OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_restore' AND (rolsuper OR rolbypassrls OR NOT rolcreatedb)) THEN
    RAISE EXCEPTION 'ipass_backup must not create databases; ipass_restore must create them and bypass nothing';
  END IF;
  IF NOT pg_has_role('ipass_app', 'ipaas_app', 'member') THEN
    RAISE EXCEPTION 'ipass_app is not a member of ipaas_app';
  END IF;
  IF (SELECT rolcanlogin FROM pg_roles WHERE rolname = 'ipaas_app') THEN
    RAISE EXCEPTION 'ipaas_app must stay NOLOGIN';
  END IF;
END $$;
SQL
