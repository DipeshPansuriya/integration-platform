-- E12-F2-S3 (T-135, row 327 lane t118): audit is kept 7 years and purged by PARTITION DROP, never by row delete
-- (architecture.md "Audit - append-only; partition drop after 7 years"; R15). Version provisional: the integrator
-- renumbers it at the seam; AuditRetentionMigrationTest finds it by its description.
--
-- public.audit_purge_expired() drops every month partition of public.audit_event (V2_2_1220) whose UPPER bound is at
-- or before now() - 7 years, so every row it held is older than 7 years; a partition that still holds a younger row
-- is kept. It returns how many partitions it dropped.
--
-- SECURITY DEFINER, owned by the migration owner (never superuser, the V2_2_4 config_approve_version pattern), with a
-- fixed search_path and every name schema-qualified: the application role gets EXECUTE and nothing else - it still
-- cannot DROP, TRUNCATE, UPDATE or DELETE the ledger itself (V2_2_1220 grants). The function takes NO argument, so no
-- caller can move the cutoff; the 7 years is RetentionPolicy.AGREED.audit() (AuditRetentionMigrationTest keeps the two
-- equal). The purge is platform-wide (partitions span tenants), so it reads no tenant setting.

CREATE OR REPLACE FUNCTION public.audit_purge_expired()
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    cutoff  timestamptz := now() - interval '7 years';
    expired record;
    dropped integer := 0;
BEGIN
    FOR expired IN
        SELECT n.nspname AS schema_name, c.relname AS partition_name
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE i.inhparent = 'public.audit_event'::regclass
          -- a range bound reads FOR VALUES FROM ('...') TO ('...'); DEFAULT or MAXVALUE bounds never match
          AND substring(pg_get_expr(c.relpartbound, c.oid) FROM 'TO \(''([^'']+)''\)')::timestamptz <= cutoff
        ORDER BY c.relname
    LOOP
        EXECUTE format('DROP TABLE %I.%I', expired.schema_name, expired.partition_name);
        dropped := dropped + 1;
    END LOOP;
    RETURN dropped;
END $$;

REVOKE ALL ON FUNCTION public.audit_purge_expired() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.audit_purge_expired() TO ipaas_app;
