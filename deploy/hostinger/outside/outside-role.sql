-- Ops script (NOT a Flyway migration). Creates / refreshes the ONE outside login role `ipass_outside` INSIDE pg-demo. Idempotent.
-- Never runs against the live database. Run as pg-demo's admin (container socket) by outside-role.sh:
--   psql -v db=ipass_demo -f -        (the password comes from the environment: OUT_PW, via \getenv - never argv, never logged)
-- The role: LOGIN, NOSUPERUSER NOBYPASSRLS, CONNECTION LIMIT 3, no memberships, SELECT on the demo tables, nothing else.
\set ON_ERROR_STOP on
\getenv out_pw OUT_PW
SELECT length(:'out_pw') >= 32 AS ok \gset pw_
\if :pw_ok
\else
  DO $$ BEGIN RAISE EXCEPTION 'OUT_PW is missing or shorter than 32 characters: nothing was changed'; END $$;
\endif

BEGIN;
-- the password literal must not reach a log, even if a statement below fails (row 473 M4)
SET LOCAL log_min_error_statement = 'panic';
SET LOCAL log_statement = 'none';
SET LOCAL password_encryption = 'scram-sha-256';
SET LOCAL client_min_messages = warning;
SELECT 'CREATE ROLE ipass_outside' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_outside') \gexec
SELECT format('ALTER ROLE ipass_outside LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT CONNECTION LIMIT 3 PASSWORD %L', :'out_pw') \gexec
-- no memberships at all
SELECT format('REVOKE %I FROM ipass_outside', r.rolname)
  FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
  WHERE m.member = (SELECT oid FROM pg_roles WHERE rolname = 'ipass_outside') \gexec

-- session defaults: a client CAN change these with SET (except temp_file_limit); the barriers are the grants below, the
-- connection limit, the proxy and the container caps. They only make an honest client well behaved.
ALTER ROLE ipass_outside SET statement_timeout = '15s';
ALTER ROLE ipass_outside SET idle_in_transaction_session_timeout = '30s';
ALTER ROLE ipass_outside SET idle_session_timeout = '10min';
ALTER ROLE ipass_outside SET lock_timeout = '2s';
ALTER ROLE ipass_outside SET default_transaction_read_only = on;
ALTER ROLE ipass_outside SET work_mem = '2MB';
ALTER ROLE ipass_outside SET temp_file_limit = '32MB';

-- connect: only this database, only this role (PUBLIC loses CONNECT here and on the two default databases)
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO ipass_outside', :'db') \gexec
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
-- schema: no CREATE for anyone but the owner; USAGE for the role only
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ipass_outside;
-- tables: SELECT only; every table of the refresh script is demo data
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO ipass_outside;
-- default privileges, tight: nothing for PUBLIC ever; the role gets SELECT on future tables only (refresh recreates them)
ALTER DEFAULT PRIVILEGES REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO ipass_outside;
-- abuse classes of the review (H3), confined to this instance and closed anyway: advisory locks, large objects
SELECT format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', p.oid::regprocedure)
  FROM pg_proc p WHERE p.pronamespace = 'pg_catalog'::regnamespace
   AND (p.proname LIKE 'pg\_%advisory\_%' OR p.proname LIKE 'lo\_%' OR p.proname IN ('loread', 'lowrite')) \gexec

-- fail loudly if the result is not what the design says
DO $$
DECLARE r pg_roles%ROWTYPE; t text;
BEGIN
  SELECT * INTO r FROM pg_roles WHERE rolname = 'ipass_outside';
  IF r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR NOT r.rolcanlogin OR r.rolconnlimit <> 3 THEN
    RAISE EXCEPTION 'ipass_outside has the wrong attributes';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_auth_members WHERE member = r.oid) THEN RAISE EXCEPTION 'ipass_outside is a member of a role'; END IF;
  IF EXISTS (SELECT 1 FROM pg_class WHERE relowner = r.oid) OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = r.oid) THEN
    RAISE EXCEPTION 'ipass_outside owns an object';
  END IF;
  IF has_schema_privilege('ipass_outside', 'public', 'CREATE') OR has_database_privilege('ipass_outside', current_database(), 'CREATE,TEMPORARY') THEN
    RAISE EXCEPTION 'ipass_outside can create objects';
  END IF;
  IF has_database_privilege('ipass_outside', 'postgres', 'CONNECT') OR has_database_privilege('ipass_outside', 'template1', 'CONNECT') THEN
    RAISE EXCEPTION 'ipass_outside can connect to another database';
  END IF;
  FOR t IN SELECT format('%I.%I', n.nspname, c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE c.relkind IN ('r','p','v','m','f','S') AND n.nspname NOT IN ('pg_catalog','information_schema','pg_toast')
  LOOP
    IF has_any_column_privilege('ipass_outside', t, 'INSERT,UPDATE,REFERENCES') OR has_table_privilege('ipass_outside', t, 'DELETE,TRUNCATE,TRIGGER') THEN
      RAISE EXCEPTION 'ipass_outside can write to %', t;
    END IF;
  END LOOP;
  IF has_function_privilege('ipass_outside', 'pg_catalog.pg_advisory_lock(bigint)', 'EXECUTE')
     OR has_function_privilege('ipass_outside', 'pg_catalog.pg_try_advisory_lock(bigint)', 'EXECUTE')
     OR has_function_privilege('ipass_outside', 'pg_catalog.lo_create(oid)', 'EXECUTE')
     OR has_function_privilege('ipass_outside', 'pg_catalog.lowrite(integer,bytea)', 'EXECUTE') THEN
    RAISE EXCEPTION 'ipass_outside can call an advisory-lock or large-object function';
  END IF;
  -- allowlist, not denylist: no column that even looks like a secret may exist in this database
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'
              AND column_name ~* '(secret|token|password|passwd|credential|hash|salt|kek|apikey|api_key|settings|payload|detail)') THEN
    RAISE EXCEPTION 'the demo database holds a secret-looking column';
  END IF;
END $$;
COMMIT;
\echo ipass_outside is in place (read-only, demo tables only)
