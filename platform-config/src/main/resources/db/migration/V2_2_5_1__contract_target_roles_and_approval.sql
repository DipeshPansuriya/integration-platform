-- E5 lane A, T-105 (ADR-011 Decisions 2 and 3; E5-C5, E5-C6): the registry phase widens the contract roles and
-- gives the contract version its approval step.
--
-- 1. Roles. V2_2_2 (phase 3) allowed SOURCE_REQUEST only and said "E5 adds the target roles by widening this
--    CHECK". This is that widening, to exactly ADR-011 Decision 2's register. Closed set as text + CHECK (R10).
-- 2. Approval. contract_version has no UPDATE grant (an APPROVED version is immutable, Decision 3 rule 1), so the
--    PENDING_APPROVAL -> APPROVED move runs in a SECURITY DEFINER function owned by the migration user, exactly the
--    V2_2_4 / V2_2_5 pattern: scoped to the CALLER'S stamped tenant (never an argument - ADR-007 Decision 6), only
--    status and approver move, and only from a PENDING_APPROVAL row. The author-is-not-approver rule answers by name
--    before the table CHECK approver_not_author would fire, so the API can refuse it as AUTHOR_IS_APPROVER. The
--    state is checked first: a DRAFT or an already decided version is NOT_PENDING_APPROVAL whoever asks.
--
-- Numbered V2_2_5_1: it must run after V2_2_2 and V2_2_5 (the tables and the submit step) on a fresh database, and
-- the config series' next whole numbers belong to other lanes (V2_2_50+ is E6).
--
-- Rollback (manual; only when no row uses a widened role):
--   DROP FUNCTION public.config_approve_contract_version(uuid, uuid);
--   ALTER TABLE public.contract DROP CONSTRAINT contract_role_check;
--   ALTER TABLE public.contract ADD CONSTRAINT contract_role_check CHECK (role IN ('SOURCE_REQUEST'));

ALTER TABLE public.contract DROP CONSTRAINT contract_role_check;
ALTER TABLE public.contract ADD CONSTRAINT contract_role_check
    CHECK (role IN ('SOURCE_REQUEST', 'SOURCE_RESPONSE', 'TARGET_REQUEST', 'TARGET_RESPONSE'));

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
    ELSIF v.author_id = p_approver_id THEN
        RETURN 'REFUSED_AUTHOR_IS_APPROVER';
    END IF;
    UPDATE public.contract_version
        SET status = 'APPROVED', approver_id = p_approver_id
        WHERE id = p_version_id;
    RETURN 'APPROVED';
END $$;

REVOKE ALL ON FUNCTION public.config_approve_contract_version(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.config_approve_contract_version(uuid, uuid) TO ipaas_app;
