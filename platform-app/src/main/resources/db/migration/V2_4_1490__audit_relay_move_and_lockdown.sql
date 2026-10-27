-- Row 544 (audit tamper-evidence, option A): the application role may no longer write the audit ledger or mark an
-- outbox row relayed by itself. The ONLY way a committed audit_outbox row reaches public.audit_event is
-- public.audit_relay_move(bigint), a SECURITY DEFINER function that sets relayed_at and inserts the ledger row in ONE
-- statement (both or neither). Version provisional: the integrator renumbers it at the seam (V2_4_1480 is the highest
-- on main when this was written).
--
-- 1. audit_outbox.trace_id (nullable): the activity writer's trace id now rides the outbox and the move copies it.
--    ADD COLUMN without a default is metadata-only and the previous application ignores it.
-- 2. public.audit_relay_move(p_outbox_id bigint) returns integer: 1 = moved, 0 = nothing moved (unknown id, another
--    tenant's row, already relayed, or locked by a concurrent relay). Pattern of audit_purge_expired (V2_4_1250):
--    SECURITY DEFINER, owned by the migration owner (never a superuser, no BYPASSRLS in production), pinned
--    search_path, every object schema-qualified, no dynamic SQL, EXECUTE revoked from PUBLIC and granted to the
--    application role only. It takes NO tenant argument: the tenant is the session's app.tenant_id, read without a
--    missing_ok flag so an unset (or blank) tenant RAISES - fail closed, like every tenant policy. FORCE RLS on both
--    tables is unchanged, so the owner is bound by the tenant policy too; the explicit tenant predicate is the
--    second gate (a superuser owner, as in test containers, bypasses RLS).
-- 3. REVOKE INSERT on audit_event and UPDATE (relayed_at) on audit_outbox from ipaas_app. SELECT on both and INSERT
--    on audit_outbox stay. Because the role then holds no UPDATE on any audit_outbox column, SELECT ... FOR UPDATE
--    on it is refused too; the relay pick is a plain ordered SELECT and the lock (SKIP LOCKED) lives in the function.
--
-- Deploy: this migration is NOT compatible with the previous application (its relay statement needs the revoked
-- privileges), so platform-app is stopped before the migration (deploy/hostinger/README.md, "Audit ledger lockdown").
--
-- ROLLBACK (Flyway Community has no undo; AuditOptionARollbackContainerTest extracts and runs exactly these lines):
-- ROLLBACK-BEGIN
-- GRANT INSERT ON public.audit_event TO ipaas_app;
-- GRANT UPDATE (relayed_at) ON public.audit_outbox TO ipaas_app;
-- DROP FUNCTION IF EXISTS public.audit_relay_move(bigint);
-- ROLLBACK-END
-- (the trace_id column is left in place: it is nullable and harmless. After the rollback, set
-- IPAAS_AUDIT_RELAY_MODE=LEGACY so the application uses the old relay statement.)

ALTER TABLE public.audit_outbox ADD COLUMN trace_id text;

CREATE OR REPLACE FUNCTION public.audit_relay_move(p_outbox_id bigint)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_tenant uuid := pg_catalog.current_setting('app.tenant_id')::uuid;
    v_moved  integer;
BEGIN
    WITH picked AS (
        SELECT o.id
        FROM public.audit_outbox o
        WHERE o.id = p_outbox_id
          AND o.relayed_at IS NULL
          AND o.tenant_id = v_tenant
        FOR UPDATE SKIP LOCKED
    ),
    marked AS (
        UPDATE public.audit_outbox o
        SET relayed_at = pg_catalog.now()
        FROM picked
        WHERE o.id = picked.id
        RETURNING o.tenant_id, o.actor, o.action, o.target_ids, o.before_state, o.after_state, o.event_at, o.trace_id
    ),
    ins AS (
        INSERT INTO public.audit_event (tenant_id, actor, action, target_ids, before_state, after_state, event_at,
                                        trace_id)
        SELECT m.tenant_id, m.actor, m.action, m.target_ids, m.before_state, m.after_state, m.event_at, m.trace_id
        FROM marked m
        RETURNING 1
    )
    SELECT pg_catalog.count(*)::integer INTO v_moved FROM ins;
    RETURN v_moved;
END $$;

-- PostgreSQL grants EXECUTE to PUBLIC by default; taken away, then granted to the application role only.
REVOKE ALL ON FUNCTION public.audit_relay_move(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.audit_relay_move(bigint) TO ipaas_app;

-- the lockdown: no direct ledger insert, no self-service relayed_at (partitions carry no grant of their own)
REVOKE INSERT ON public.audit_event FROM ipaas_app;
REVOKE UPDATE (relayed_at) ON public.audit_outbox FROM ipaas_app;
