-- E14 T-142 / T-189 (row 457 / 495; ADR-014 Decision 3, ADR-012 section 4, ADR-016, architecture control T6).
-- Payload retention is 30 days (decided by Dipesh 2026-10-12): the day partitions message_D, payload_D and delivery_D are
-- dropped together by PARTITION DROP, never a row DELETE, and only while delivery_D holds 0 deliveries that are
-- DEAD_LETTERED or not final (a DELIVERED delivery is the one final state that is safe to lose).
--
-- 1. The maintenance role: NOLOGIN, holds grants only. The deployment's login (IPASS_MAINTENANCE_DB_USERNAME) is created
--    outside Flyway and granted membership, so no password enters a migration (R07). Same shape as V1_3_1; a role that
--    already exists with more power is refused, and one made by another migration (T-135b) is simply reused.
-- 2. public.payload_purge_days(cutoff, dry_run): SECURITY DEFINER, owned by the migration owner (the table owner, so the
--    guard counts every tenant's deliveries - inactive tenants included - through the non-FORCE row-level security),
--    fixed search_path, 5 s lock_timeout. EXECUTE goes to ipaas_maintenance ONLY; the application role gets none (T6).
--    The retention floor is inside the function too: a cutoff newer than today (UTC) minus 30 days raises, so no caller
--    can purge younger data. 30 days = RetentionPolicy.AGREED.payload() (PayloadPurgeContainerTest keeps the two equal).
--    The guard count and the drop run in one transaction under an ACCESS EXCLUSIVE lock on delivery_D, so a delivery
--    cannot change state between the count and the drop. Dry run counts and locks but drops nothing.
--    Returns one row per day: DROPPED, WOULD_DROP (dry run) or KEPT (guard). No tenant id, no payload leaves it.

DO $$
DECLARE
    existing pg_catalog.pg_roles%ROWTYPE;
BEGIN
    SELECT * INTO existing FROM pg_catalog.pg_roles WHERE rolname = 'ipaas_maintenance';
    IF NOT FOUND THEN
        CREATE ROLE ipaas_maintenance NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION;
    ELSIF existing.rolsuper OR existing.rolbypassrls OR existing.rolcreatedb OR existing.rolcreaterole
          OR existing.rolreplication OR existing.rolcanlogin THEN
        RAISE EXCEPTION 'role ipaas_maintenance exists with privileges it must not hold (login, superuser, bypassrls, createdb, createrole or replication)';
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION public.payload_purge_days(p_cutoff date, p_dry_run boolean)
RETURNS TABLE (purge_day date, outcome text)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET lock_timeout = '5s'
AS $$
DECLARE
    d        date;
    suffix   text;
    blocking bigint;
BEGIN
    IF p_cutoff IS NULL OR p_cutoff > ((now() AT TIME ZONE 'UTC')::date - 30) THEN
        RAISE EXCEPTION 'payload purge cutoff % is inside the 30-day retention', p_cutoff USING ERRCODE = '22023';
    END IF;
    FOR d IN
        SELECT DISTINCT to_date(substring(c.relname FROM '(\d{8})$'), 'YYYYMMDD') AS day_of
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND i.inhparent IN ('public.message'::regclass, 'public.payload'::regclass, 'public.delivery'::regclass)
          AND c.relname ~ '^(message|payload|delivery)_\d{8}$'
        ORDER BY day_of
    LOOP
        EXIT WHEN d >= p_cutoff;
        suffix := to_char(d, 'YYYYMMDD');
        blocking := 0;
        IF to_regclass('public.delivery_' || suffix) IS NOT NULL THEN
            EXECUTE format('LOCK TABLE public.%I IN ACCESS EXCLUSIVE MODE', 'delivery_' || suffix);
            EXECUTE format('SELECT count(*) FROM public.%I WHERE state <> ''DELIVERED''', 'delivery_' || suffix)
                INTO blocking;
        END IF;
        purge_day := d;
        IF blocking > 0 THEN
            outcome := 'KEPT';
        ELSIF p_dry_run THEN
            outcome := 'WOULD_DROP';
        ELSE
            EXECUTE format('DROP TABLE IF EXISTS public.%I, public.%I, public.%I',
                'message_' || suffix, 'payload_' || suffix, 'delivery_' || suffix);
            outcome := 'DROPPED';
        END IF;
        RETURN NEXT;
    END LOOP;
END $$;

REVOKE ALL ON FUNCTION public.payload_purge_days(date, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payload_purge_days(date, boolean) TO ipaas_maintenance;

-- Rollback (manual, owner only; Flyway community has no undo): DROP FUNCTION public.payload_purge_days(date, boolean);
