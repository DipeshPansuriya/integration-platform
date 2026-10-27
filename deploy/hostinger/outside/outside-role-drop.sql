-- Ops script (NOT a Flyway migration): removes everything outside-role.sql created. Idempotent. Run as the bootstrap
-- superuser connected to the ipass database:  psql -v db=<database> -f -
\set ON_ERROR_STOP on
BEGIN;
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'ipass_outside';
SELECT format('DROP POLICY IF EXISTS outside_pin ON %I.%I', schemaname, tablename) FROM pg_policies WHERE policyname = 'outside_pin' \gexec
SELECT 'DROP OWNED BY ipass_outside' WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_outside') \gexec
SELECT format('ALTER ROLE ipass_outside IN DATABASE %I RESET ALL', :'db') WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_outside') \gexec
SELECT 'DROP ROLE ipass_outside' WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_outside') \gexec
-- back to the default: PUBLIC may create temp tables again
SELECT format('GRANT TEMPORARY ON DATABASE %I TO PUBLIC', :'db') \gexec
COMMIT;
\echo ipass_outside is gone
