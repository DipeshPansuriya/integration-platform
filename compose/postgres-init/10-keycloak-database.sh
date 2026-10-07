#!/bin/sh
# ADR-007 Decision 1: Keycloak gets its own database and login on the shared PostgreSQL server (development
# compose only). Runs once, on the first start of an empty data volume (docker-entrypoint-initdb.d).
# R07: the password arrives as an environment variable value (from the OS secret store via compose) and is
# passed to psql as a variable - never written to a file; format() quotes the identifier and literal.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v kc_db="$KEYCLOAK_DB_NAME" -v kc_user="$KEYCLOAK_DB_USERNAME" -v kc_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD %L',
              :'kc_user', :'kc_password') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'kc_db', :'kc_user') \gexec
SQL
