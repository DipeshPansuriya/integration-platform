-- E1 lane C, T-157 (ADR-009 Decision 3, ADR-007 Decision 2, architecture.md section 5 control T5).
-- The one trusted path across the pre-tenant boundary for humans: resolves the (issuer, subject) pair of a
-- validated token to its user and tenant before any tenant is bound. Owned by the migration owner, which
-- bypasses row-level security (no FORCE, see V1_3_2). Narrow projection only, ACTIVE users of ACTIVE
-- tenants only. search_path pinned and every name schema-qualified (the definer hijack guard).

CREATE FUNCTION public.ipaas_login_bootstrap(p_issuer text, p_subject text)
RETURNS TABLE (
    user_id       uuid,
    user_status   text,
    tenant_id     uuid,
    tenant_code   text,
    tenant_status text,
    time_zone     text
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT u.id, u.status, t.id, t.code, t.status, t.time_zone
      FROM public.app_user u
      JOIN public.tenant t ON t.id = u.tenant_id
     WHERE u.issuer = p_issuer
       AND u.subject = p_subject
       AND u.status = 'ACTIVE'
       AND t.status = 'ACTIVE';
$$;

-- The function is the whole pre-tenant boundary; nobody but the application role gets it.
REVOKE ALL ON FUNCTION public.ipaas_login_bootstrap(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.ipaas_login_bootstrap(text, text) TO ipaas_app;
