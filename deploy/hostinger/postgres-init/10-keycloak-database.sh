#!/bin/sh
# Keycloak's own database and login on the shared server (ADR-007 D1). Idempotent; runs on the first start of an
# empty volume and may be re-run by hand:
#   docker compose exec -T postgres sh /docker-entrypoint-initdb.d/10-keycloak-database.sh
# The password is passed to psql as a variable, never written to a file; format() quotes identifier and literal.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v kc_db="$KEYCLOAK_DB_NAME" -v kc_user="$KEYCLOAK_DB_USERNAME" -v kc_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS', :'kc_user')
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'kc_user') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'kc_user', :'kc_password') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'kc_db', :'kc_user')
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'kc_db') \gexec
SQL
