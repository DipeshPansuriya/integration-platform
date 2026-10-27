-- Ops script (NOT a Flyway migration). Creates / refreshes the ONE outside login role `ipass_outside`. Idempotent.
-- Run as the bootstrap superuser, connected to the ipass database, by outside-role.sh:
--   psql -v db=<database> -v tenant_code=<tenant it may read> -f -      (the password comes from the environment: OUT_PW)
-- What the role is: LOGIN, 3 connections, read-only, SELECT on nine tables (named columns only) and only for ONE tenant.
-- It is not a member of any role, so it inherits nothing from ipaas_app. Nothing here touches another role's grants,
-- except TEMPORARY on the database (PUBLIC loses it; the roles that need it keep it explicitly).
\set ON_ERROR_STOP on
\getenv out_pw OUT_PW
SELECT count(*) = 1 AS ok FROM public.tenant WHERE code = :'tenant_code' \gset tenant_
\if :tenant_ok
\else
  DO $$ BEGIN RAISE EXCEPTION 'tenant code not found: nothing was changed'; END $$;
\endif
SELECT id AS tenant_id FROM public.tenant WHERE code = :'tenant_code' \gset

BEGIN;
SET LOCAL password_encryption = 'scram-sha-256';
SET LOCAL client_min_messages = warning;
SELECT 'CREATE ROLE ipass_outside' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_outside') \gexec
SELECT format('ALTER ROLE ipass_outside LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT CONNECTION LIMIT 3 PASSWORD %L', :'out_pw') \gexec
-- no memberships at all (a stray GRANT <role> TO ipass_outside is removed on every run)
SELECT format('REVOKE %I FROM ipass_outside', r.rolname)
  FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
  WHERE m.member = (SELECT oid FROM pg_roles WHERE rolname = 'ipass_outside') \gexec

-- session limits (defaults a client can lower or lift again with SET; the hard caps are CONNECTION LIMIT, the proxy and temp_file_limit)
ALTER ROLE ipass_outside SET statement_timeout = '15s';
ALTER ROLE ipass_outside SET idle_in_transaction_session_timeout = '30s';
ALTER ROLE ipass_outside SET idle_session_timeout = '10min';
ALTER ROLE ipass_outside SET lock_timeout = '2s';
ALTER ROLE ipass_outside SET default_transaction_read_only = on;
ALTER ROLE ipass_outside SET temp_file_limit = '32MB';
ALTER ROLE ipass_outside SET work_mem = '4MB';
ALTER ROLE ipass_outside SET row_security = on;
-- the tenant context of this role (the RLS policies read app.tenant_id); restrictive policies below pin it for real
SELECT format('ALTER ROLE ipass_outside IN DATABASE %I SET app.tenant_id = %L', :'db', :'tenant_id') \gexec

-- start from nothing in schema public, then grant the justified set
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM ipass_outside;
GRANT USAGE ON SCHEMA public TO ipass_outside;
GRANT SELECT (id, code, status, time_zone) ON public.tenant TO ipass_outside;
GRANT SELECT (id, tenant_id, code, name, direction, type) ON public.system TO ipass_outside;          -- not settings
GRANT SELECT (id, tenant_id, system_id, code, name, base_url, http_method, auth_method, timeout_ms,
              circuit_failure_threshold, circuit_open_seconds, idempotent_receive)
  ON public.target_endpoint TO ipass_outside;                                                        -- not credential_ref, error_dictionary
GRANT SELECT (id, tenant_id, code, name, mode, ordering, current_version, published_version, status, created_at, updated_at)
  ON public.interface TO ipass_outside;
GRANT SELECT (id, tenant_id, interface_id, target_endpoint_id, sequence, is_primary, status, created_at, updated_at)
  ON public.route TO ipass_outside;
GRANT SELECT (id, tenant_id, interface_id, interface_version_id, source_contract_version_id, business_key, key_seq, state,
              accepted_at, routed_at, updated_at)
  ON public.message TO ipass_outside;                                                                -- no payload table at all
GRANT SELECT (id, tenant_id, state, created_at, updated_at, next_attempt_at, dead_letter_class, bulkhead_deferrals,
              interface_id, business_key, key_seq, target_id)
  ON public.delivery TO ipass_outside;                                                               -- not claim_token
GRANT SELECT (id, tenant_id, delivery_id, layer, outcome_class, started_at, finished_at, created_at)
  ON public.delivery_attempt TO ipass_outside;                                                       -- not detail (target error text)
GRANT SELECT ON public.delivery_daily_aggregate TO ipass_outside;

-- the real tenant pin: a RESTRICTIVE policy is AND-ed with the app's permissive one, so SET app.tenant_id to another value shows nothing
SELECT format('DROP POLICY IF EXISTS outside_pin ON public.%I', v.t)
  FROM (VALUES ('tenant'),('system'),('target_endpoint'),('interface'),('route'),('message'),('delivery'),('delivery_attempt'),('delivery_daily_aggregate')) v(t) \gexec
SELECT format('CREATE POLICY outside_pin ON public.%I AS RESTRICTIVE FOR SELECT TO ipass_outside USING (%I = %L::uuid)', v.t, v.c, :'tenant_id')
  FROM (VALUES ('tenant','id'),('system','tenant_id'),('target_endpoint','tenant_id'),('interface','tenant_id'),('route','tenant_id'),
               ('message','tenant_id'),('delivery','tenant_id'),('delivery_attempt','tenant_id'),('delivery_daily_aggregate','tenant_id')) v(t,c) \gexec

-- no temp tables for the outside role: PUBLIC loses TEMPORARY on this database, the roles that use it keep it explicitly
SELECT format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', :'db') \gexec
SELECT format('GRANT TEMPORARY ON DATABASE %I TO %I', :'db', r) FROM unnest(ARRAY['ipaas_app','ipass_backup','ipass_restore']) r
  WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) \gexec

-- fail loudly if the result is not what the design says
DO $$
DECLARE r pg_roles%ROWTYPE; t text; c text;
BEGIN
  SELECT * INTO r FROM pg_roles WHERE rolname = 'ipass_outside';
  IF r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR NOT r.rolcanlogin OR r.rolconnlimit <> 3 THEN
    RAISE EXCEPTION 'ipass_outside has the wrong attributes';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_auth_members WHERE member = r.oid) THEN RAISE EXCEPTION 'ipass_outside is a member of a role'; END IF;
  IF (SELECT count(*) FROM pg_policies WHERE policyname = 'outside_pin' AND permissive = 'RESTRICTIVE') <> 9 THEN RAISE EXCEPTION 'tenant pin policies missing'; END IF;
  FOR t IN SELECT unnest(ARRAY['public.source_credential','public.target_credential','public.app_user','public.role_grant','public.audit_event',
                               'public.audit_outbox','public.payload','public.outbox','public.idempotency_key','public.intake_idempotency',
                               'public.mapping_sample_run','public.mapping_version','public.contract_version','public.db_target_approved_table',
                               'scheduler.operator_credential','scheduler.scheduled_tasks','scheduler.job_definition',
                               'console_session.spring_session','console_session.spring_session_attributes'])
  LOOP
    IF to_regclass(t) IS NOT NULL AND has_any_column_privilege('ipass_outside', t, 'SELECT,INSERT,UPDATE,REFERENCES') THEN
      RAISE EXCEPTION 'ipass_outside can reach %', t;
    END IF;
  END LOOP;
  FOR t IN SELECT format('%I.%I', n.nspname, c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE c.relkind IN ('r','p','v','m','f') AND n.nspname NOT IN ('pg_catalog','information_schema')
  LOOP
    IF has_any_column_privilege('ipass_outside', t, 'INSERT,UPDATE,REFERENCES') OR has_table_privilege('ipass_outside', t, 'DELETE,TRUNCATE,TRIGGER') THEN
      RAISE EXCEPTION 'ipass_outside can write to %', t;
    END IF;
  END LOOP;
  FOR t, c IN VALUES ('public.delivery','claim_token'),('public.target_endpoint','credential_ref'),('public.system','settings'),('public.delivery_attempt','detail')
  LOOP
    IF has_column_privilege('ipass_outside', t, c, 'SELECT') THEN RAISE EXCEPTION 'ipass_outside can read %.%', t, c; END IF;
  END LOOP;
END $$;
COMMIT;
\echo ipass_outside is in place (tenant pinned, read-only, no secret tables)
