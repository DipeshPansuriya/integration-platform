-- E16 F3 hardening, Karan re-test S1 (CWE-863): V2_4_1330 granted the app role INSERT on every column and its single
-- row-level-security policy tested only tenant_id, so SQL running as ipaas_app could INSERT a row that is already
-- APPROVED (the state CHECK accepts any non-null decided_by) and the send path would honour it - the "a decision moves
-- only through the definer function" statement was false for INSERT.
--
-- This migration replaces the one catch-all policy with one policy per command. An INSERT is now accepted only as a
-- fresh request: status PENDING_APPROVAL and no decider or revoker fields. APPROVED and REVOKED are reachable only
-- through public.config_decide_db_table_approval, which runs as the table owner (still subject to FORCE ROW LEVEL
-- SECURITY, hence its own UPDATE policy; ipaas_app holds no UPDATE or DELETE grant, so that policy is unreachable
-- for it). V2_4_1330 is applied and is not edited.
--
-- Rollback (manual): DROP the three policies below and re-create db_target_approved_table_tenant_isolation as in
-- V2_4_1330 (USING and WITH CHECK on tenant_id).

DROP POLICY db_target_approved_table_tenant_isolation ON public.db_target_approved_table;

CREATE POLICY db_target_approved_table_select ON public.db_target_approved_table
    FOR SELECT USING (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE POLICY db_target_approved_table_insert_pending ON public.db_target_approved_table
    FOR INSERT WITH CHECK (
        tenant_id = current_setting('app.tenant_id')::uuid
        AND status = 'PENDING_APPROVAL'
        AND decided_by IS NULL AND decided_at IS NULL
        AND revoked_by IS NULL AND revoked_at IS NULL);

CREATE POLICY db_target_approved_table_update ON public.db_target_approved_table
    FOR UPDATE USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- S5 (Karan re-test): the target fingerprint also covers sslmode, so weakening verify-full to require (or dropping it)
-- invalidates the approval like a repoint does. The fingerprint has ONE definition - this function; the send path, the
-- publish check, the request insert and the decision all call it - so replacing it here changes every reader at once.
-- The stored hashes of existing rows are re-computed against the new definition ONLY where they still equal the OLD
-- four-field hash of the system's CURRENT settings (per tenant, tenant stamped for FORCE ROW LEVEL SECURITY): that row
-- is bound to the target it points at today, so it keeps its state and merely moves to the new formula. A row that was
-- already stale - the system was repointed after the request or approval - keeps its old hash and stays stale: the
-- upgrade never revives an invalidated approval for the new target (Karan F-1).
CREATE OR REPLACE FUNCTION public.db_target_fingerprint(p_settings jsonb) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE SET search_path = pg_catalog, pg_temp AS $$
    SELECT pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
        coalesce(p_settings ->> 'host', '') || E'' || coalesce(p_settings ->> 'port', '') || E''
        || coalesce(p_settings ->> 'database', '') || E'' || coalesce(p_settings ->> 'user', '')
        || E'' || coalesce(p_settings ->> 'sslmode', ''),
        'UTF8')), 'hex')
$$;

DO $rehash$
DECLARE
    tenant uuid;
BEGIN
    FOR tenant IN SELECT id FROM public.tenant LOOP
        PERFORM set_config('app.tenant_id', tenant::text, true);
        UPDATE public.db_target_approved_table a SET target_hash = public.db_target_fingerprint(s.settings)
            FROM public.system s
            WHERE a.tenant_id = tenant AND s.id = a.system_id AND s.tenant_id = a.tenant_id
              AND a.target_hash = pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
                  coalesce(s.settings ->> 'host', '') || E'' || coalesce(s.settings ->> 'port', '') || E''
                  || coalesce(s.settings ->> 'database', '') || E'' || coalesce(s.settings ->> 'user', ''),
                  'UTF8')), 'hex');
    END LOOP;
END
$rehash$;
