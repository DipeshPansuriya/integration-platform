-- E12-F2-S3 (T-135, row 327 lane t118): audit is kept 7 years and purged by PARTITION DROP, never by row delete
-- (architecture.md "Audit - append-only; partition drop after 7 years"; R15). Version provisional: the integrator
-- renumbers it at the seam; AuditRetentionMigrationTest finds it by its description.
--
-- public.audit_purge_expired() drops every month partition of public.audit_event (V2_2_1220) whose UPPER bound is at
-- or before now() - 7 years, so every row it held is older than 7 years; a partition that still holds a younger row
-- is kept. It returns how many partitions it dropped.
--
-- SECURITY DEFINER, owned by the migration owner (never superuser, the V2_2_4 config_approve_version pattern), with a
-- fixed search_path, every name schema-qualified, and a 5 s lock_timeout so a busy ledger makes the drop fail fast
-- instead of queueing every audit insert behind its lock. GRANTED TO NOBODY: the application role runs no DDL
-- (architecture.md control T6, ADR-011 Decision 8, ADR-016), so it gets no EXECUTE; the maintenance role that will
-- call the purge, and its grant, come with T-135b. Until then only the owner can run it. The function takes NO
-- argument, so no caller can move the cutoff; the 7 years is RetentionPolicy.AGREED.audit()
-- (AuditRetentionMigrationTest keeps the two equal). The purge is platform-wide (partitions span tenants), so it reads
-- no tenant setting.

CREATE OR REPLACE FUNCTION public.audit_purge_expired()
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET lock_timeout = '5s'
AS $$
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

-- PostgreSQL grants EXECUTE to PUBLIC by default; taken away, and nothing is granted in its place (T-135b).
REVOKE ALL ON FUNCTION public.audit_purge_expired() FROM PUBLIC;
