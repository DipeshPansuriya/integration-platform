-- Single-user self-approval (project owner, 2026-10-26; plan PLAN-single-user-self-approval): one TENANT_ADMIN can create,
-- submit, approve and publish a flow version, a contract version and a DB-table approval without a second user.
-- This DELIBERATELY weakens separation of duties (four-eyes); the role check (APPROVE_VERSION, APPROVE_DB_TABLE) still
-- decides WHO may approve, only the "not the author / not the requester" rule goes. V2_2_2, V2_2_4, V2_2_5_1 and
-- V2_4_1330 are applied and are not edited; this migration replaces what they created.
--
--   1. DROP the three table CHECKs that forbid it:
--        interface_version.approver_not_author        (V2_2_4)
--        contract_version.approver_not_author         (V2_2_2)
--        db_target_approved_table_four_eyes           (V2_4_1330)
--      Without the third, approving a DB table as its requester would still fail with a CHECK violation (23514).
--   2. REPLACE the three SECURITY DEFINER approval functions with their latest definitions minus the author / requester
--      branch. Everything else is kept exactly: the tenant scoping from the stamped session, the state check first,
--      the config_version_refusal record for NOT_PENDING_APPROVAL, the target-changed check and the whole REVOKE path.
--      CREATE OR REPLACE keeps each function's owner and its GRANT EXECUTE to ipaas_app.
--
-- Kept on purpose: config_version_refusal and its CHECK (it still records NOT_PENDING_APPROVAL; the value
-- AUTHOR_IS_APPROVER stays allowed so old rows remain valid but is never written again); the db_target_approved_table_state
-- CHECK (an APPROVED row still needs a decider).
--
-- Rollback (manual; Flyway Community has no undo). Prerequisite: no row has approver_id = author_id (interface_version,
-- contract_version) or decided_by = requested_by (db_target_approved_table), else the ADD CONSTRAINT fails - repoint those
-- rows first. Then re-add the three constraints and restore the three functions from V2_2_4:124-148, V2_2_5_1:25-45
-- and V2_4_1330 (the author / requester branches):
--   ALTER TABLE public.interface_version ADD CONSTRAINT approver_not_author CHECK (approver_id IS NULL OR approver_id <> author_id);
--   ALTER TABLE public.contract_version ADD CONSTRAINT approver_not_author CHECK (approver_id IS NULL OR approver_id <> author_id);
--   ALTER TABLE public.db_target_approved_table ADD CONSTRAINT db_target_approved_table_four_eyes CHECK (decided_by IS DISTINCT FROM requested_by);

ALTER TABLE public.interface_version DROP CONSTRAINT approver_not_author;
ALTER TABLE public.contract_version DROP CONSTRAINT approver_not_author;
ALTER TABLE public.db_target_approved_table DROP CONSTRAINT db_target_approved_table_four_eyes;

-- Flow versions (was V2_2_4): the author_id = p_approver_id branch is gone.
CREATE OR REPLACE FUNCTION public.config_approve_version(p_version_id uuid, p_approver_id uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v public.interface_version%ROWTYPE;
BEGIN
    SELECT * INTO v FROM public.interface_version
        WHERE id = p_version_id
          AND tenant_id = current_setting('app.tenant_id')::uuid
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'VERSION_NOT_FOUND';
    ELSIF v.status <> 'PENDING_APPROVAL' THEN
        INSERT INTO public.config_version_refusal (tenant_id, version_id, refusal, actor_id)
            VALUES (v.tenant_id, p_version_id, 'NOT_PENDING_APPROVAL', p_approver_id);
        RETURN 'REFUSED_' || v.status;
    END IF;
    UPDATE public.interface_version
        SET status = 'APPROVED', approver_id = p_approver_id, decided_at = now()
        WHERE id = p_version_id;
    RETURN 'APPROVED';
END $$;

-- Contract versions (was V2_2_5_1): the author_id = p_approver_id branch is gone.
CREATE OR REPLACE FUNCTION public.config_approve_contract_version(p_version_id uuid, p_approver_id uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v public.contract_version%ROWTYPE;
BEGIN
    SELECT * INTO v FROM public.contract_version
        WHERE id = p_version_id
          AND tenant_id = current_setting('app.tenant_id')::uuid
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'VERSION_NOT_FOUND';
    ELSIF v.status <> 'PENDING_APPROVAL' THEN
        RETURN 'REFUSED_' || v.status;
    END IF;
    UPDATE public.contract_version
        SET status = 'APPROVED', approver_id = p_approver_id
        WHERE id = p_version_id;
    RETURN 'APPROVED';
END $$;

-- DB-table approvals (was V2_4_1330; V2_4_1340 did not touch this function): the requested_by = actor branch is gone.
-- Answers: APPROVED | REVOKED | NOT_FOUND | REFUSED_TARGET_CHANGED | REFUSED_NO_ACTOR | REFUSED_UNKNOWN_DECISION |
-- REFUSED_<status>. REFUSED_SELF_APPROVAL is no longer returned.
CREATE OR REPLACE FUNCTION public.config_decide_db_table_approval(p_id uuid, p_decision text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    r       public.db_target_approved_table%ROWTYPE;
    actor   text := nullif(pg_catalog.current_setting('app.actor_id', true), '');
    current_hash text;
BEGIN
    IF p_decision IS NULL OR p_decision NOT IN ('APPROVE', 'REVOKE') THEN
        RETURN 'REFUSED_UNKNOWN_DECISION';
    END IF;
    IF actor IS NULL THEN
        RETURN 'REFUSED_NO_ACTOR';
    END IF;
    SELECT * INTO r FROM public.db_target_approved_table
        WHERE id = p_id
          AND tenant_id = pg_catalog.current_setting('app.tenant_id')::uuid
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF p_decision = 'APPROVE' THEN
        IF r.status <> 'PENDING_APPROVAL' THEN
            RETURN 'REFUSED_' || r.status;
        END IF;
        SELECT public.db_target_fingerprint(s.settings) INTO current_hash FROM public.system s
            WHERE s.id = r.system_id AND s.tenant_id = r.tenant_id;
        IF current_hash IS DISTINCT FROM r.target_hash THEN
            RETURN 'REFUSED_TARGET_CHANGED';
        END IF;
        UPDATE public.db_target_approved_table
            SET status = 'APPROVED', decided_by = actor, decided_at = pg_catalog.now()
            WHERE id = p_id;
        RETURN 'APPROVED';
    END IF;
    IF r.status <> 'APPROVED' THEN
        RETURN 'REFUSED_' || r.status;
    END IF;
    UPDATE public.db_target_approved_table
        SET status = 'REVOKED', revoked_by = actor, revoked_at = pg_catalog.now()
        WHERE id = p_id;
    RETURN 'REVOKED';
END $$;
