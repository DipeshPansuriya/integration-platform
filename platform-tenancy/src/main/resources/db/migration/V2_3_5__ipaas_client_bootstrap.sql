-- E4 lane C, T-098 (ADR-007 Decision 2, ADR-009 "Update 2026-10-02", architecture.md section 5 control T5).
-- The trusted pre-tenant path for source systems, the machine twin of V1_3_3: resolves the (issuer, client id from
-- the azp claim) pair of a validated machine token to its credential, source system, customer identity and tenant
-- before any tenant is bound. Owned by the migration owner, which bypasses row-level security (no FORCE, see
-- V2_3_4). Narrow projection only, ACTIVE credentials of ACTIVE tenants only - so a revoke bites from its commit on,
-- whatever the token's expiry. search_path pinned and every name schema-qualified (the definer hijack guard).

CREATE FUNCTION public.ipaas_client_bootstrap(p_issuer text, p_client_id text)
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
       AND t.status = 'ACTIVE';
$$;

-- The function is the whole pre-tenant boundary for machines; nobody but the application role gets it.
REVOKE ALL ON FUNCTION public.ipaas_client_bootstrap(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.ipaas_client_bootstrap(text, text) TO ipaas_app;
