-- E14 T-142 (row 495, decided by Mira): the PAYLOAD_PURGE guard becomes CONSERVATIVE. V2_4_1450 (already on main, so
-- never edited - Flyway checksums applied files) kept a day only for a DEAD_LETTERED or non-final delivery. This
-- replaces public.payload_purge_days(date, boolean) so a day that still holds any message that is not ROUTED
-- (ACCEPTED = never routed / non-final, ROUTE_FAILED = may need a fix and replay) is kept too: the payload is the only
-- copy, and we do not drop data we cannot prove final. Stricter than ADR-012:316; Mahesh ratifies (ADR-012 amendment
-- 2026-10-27). Same signature, owner, search_path, lock_timeout and grants (EXECUTE to ipaas_maintenance only).
-- The job still ships OFF (ipaas.retention.payload-purge.mode); nothing here turns it on.

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
        -- Mira's conservative guard (row 495, stricter than ADR-012:316 - Mahesh ratifies): a day that still holds a
        -- message that is not ROUTED (ACCEPTED = never routed / non-final, ROUTE_FAILED = may need a fix and replay)
        -- is kept too; its payload is the only copy.
        IF blocking = 0 AND to_regclass('public.message_' || suffix) IS NOT NULL THEN
            EXECUTE format('LOCK TABLE public.%I IN ACCESS EXCLUSIVE MODE', 'message_' || suffix);
            EXECUTE format('SELECT count(*) FROM public.%I WHERE state <> ''ROUTED''', 'message_' || suffix)
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

-- Rollback (manual, owner only): re-apply the function body of V2_4_1450.
