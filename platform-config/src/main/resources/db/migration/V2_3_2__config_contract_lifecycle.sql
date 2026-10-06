-- E3 lane C, T-019: the contract version's submit step. Lane B's phase-3 cut (V2_2_2) left
-- contract_version with SELECT+INSERT only for the application role - correct for immutability, but it
-- gave the DRAFT -> PENDING_APPROVAL walk (the T-093 node the lifecycle API keeps, and the change-note
-- the submit body carries) nowhere to live. This migration is the additive fix inside lane C's own
-- prefix, following V2_2_4's established pattern exactly: a nullable column added by the owning lane's
-- migration (the fixed contract V2_2_1 allows it), and a SECURITY DEFINER function owned by the
-- migration user that moves the lifecycle columns, scoped to the CALLER'S stamped tenant, never to a
-- caller argument (ADR-007 Decision 6). Content (the schema itself) stays unreachable: like
-- config_approve_version, only status and the change note move, and only from a DRAFT row.
--
-- Flagged back through arjun-fullstack in the T-015 handoff: if lane B prefers to amend V2_2_2 itself
-- instead, this file is a single revert away (DROP FUNCTION, then DROP COLUMN).

ALTER TABLE public.contract_version ADD COLUMN change_note text;

CREATE OR REPLACE FUNCTION public.config_submit_contract_version(p_version_id uuid, p_change_note text)
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
    ELSIF v.status <> 'DRAFT' THEN
        RETURN 'REFUSED_' || v.status;
    END IF;
    UPDATE public.contract_version
        SET status = 'PENDING_APPROVAL', change_note = p_change_note
        WHERE id = p_version_id;
    RETURN 'SUBMITTED';
END $$;

REVOKE ALL ON FUNCTION public.config_submit_contract_version(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.config_submit_contract_version(uuid, text) TO ipaas_app;
