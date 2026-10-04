-- E1 lane C, T-005 (architecture.md section 5, control T6; ADR-009; rule R07).
-- The restricted application role. It is a NOLOGIN role that only holds grants: the deployment's login
-- (spring.datasource.username, env name DB_USERNAME) is created outside Flyway and granted membership, so no
-- password ever enters a migration (R07). It never owns a table, so row-level security always applies to it
-- without FORCE (the definer bootstrap of V1_3_3 relies on the table owner bypassing RLS).
--
-- Runs as the Flyway owner login (spring.flyway.user, env name DB_MIGRATION_USERNAME): a superuser, or a
-- non-superuser that owns the database and holds CREATEROLE.

DO $$
DECLARE
    existing pg_catalog.pg_roles%ROWTYPE;
BEGIN
    SELECT * INTO existing FROM pg_catalog.pg_roles WHERE rolname = 'ipaas_app';
    IF NOT FOUND THEN
        CREATE ROLE ipaas_app NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION;
    ELSIF existing.rolsuper OR existing.rolbypassrls OR existing.rolcreatedb OR existing.rolcreaterole
          OR existing.rolreplication OR existing.rolcanlogin THEN
        -- Roles are cluster-wide: one made elsewhere with more power is refused, never silently accepted.
        RAISE EXCEPTION 'role ipaas_app exists with privileges it must not hold (login, superuser, bypassrls, createdb, createrole or replication)';
    END IF;
END
$$;

-- No DDL for anyone but the owner (T6). PostgreSQL 15+ already ships this default; it is stated so an
-- older or hand-changed database is brought to the same state.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
