-- E5 lane C, T-103 (rohit-developer, ledger row 278; ADR-007 Decision 3). Rotation of a source system's machine
-- credential = issue a second credential; both stay valid during a grace window (default 7 days), then the old one is
-- revoked. V2_3_4's fixed contract allows ADDED columns (nullable or defaulted), never renamed or dropped:
--
--   revoke_after  when a credential being rotated stops; NULL for every other credential. Only an ACTIVE row is
--                 scheduled (a PENDING one is not usable yet; a REVOKED one already stopped - its value is history).
--
-- The client bootstrap (V2_3_5) is replaced with the SAME signature, owner, volatility, definer attributes and pinned
-- search_path; its one new predicate refuses a credential whose revoke_after has passed - so the grace window ends at
-- the platform at that instant, even before the reconciliation job (E14 T-203) flips the row to REVOKED and disables
-- the client. CREATE OR REPLACE keeps the function's grants (EXECUTE for ipaas_app only) unchanged.
--
-- Grants: the application may set revoke_after (the rotation) - one column-level UPDATE grant; the table-level grants
-- (SELECT, INSERT) and the denied DELETE/TRUNCATE are unchanged. Flyway runs this file in one transaction.

ALTER TABLE public.source_credential
    ADD COLUMN revoke_after timestamptz,
    ADD CONSTRAINT source_credential_revoke_after_check CHECK (revoke_after IS NULL OR status <> 'PENDING');

GRANT UPDATE (revoke_after) ON public.source_credential TO ipaas_app;

CREATE OR REPLACE FUNCTION public.ipaas_client_bootstrap(p_issuer text, p_client_id text)
RETURNS TABLE (
    credential_id     uuid,
    credential_status text,
    source_system_id  uuid,
    customer_id       text,
    tenant_id         uuid,
    tenant_code       text,
    tenant_status     text,
    time_zone         text
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT c.id, c.status, c.source_system_id, c.customer_id, t.id, t.code, t.status, t.time_zone
      FROM public.source_credential c
      JOIN public.tenant t ON t.id = c.tenant_id
     WHERE c.issuer = p_issuer
       AND c.client_id = p_client_id
       AND c.status = 'ACTIVE'
       AND (c.revoke_after IS NULL OR c.revoke_after > now())
       AND t.status = 'ACTIVE';
$$;

-- Rollback (manual, never run by Flyway): re-create V2_3_5's function body (without the revoke_after predicate), then
--   REVOKE UPDATE (revoke_after) ON public.source_credential FROM ipaas_app;
--   ALTER TABLE public.source_credential DROP CONSTRAINT source_credential_revoke_after_check,
--       DROP COLUMN revoke_after;
