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
