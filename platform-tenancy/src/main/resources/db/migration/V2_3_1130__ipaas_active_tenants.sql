-- E11 lane C (row 278 T106, T-049 composition): the trusted directory read every scanning job loops over (ADR-011
-- Decision 8: "loop over tenants returned by a definer function ipaas_active_tenants() (ids only), binding one tenant
-- per transaction"). The outbox relay is its first production caller. The application role sees only its own
-- directory row (V1_3_2 tenant_dir_self), so no app-path query can list the tenants - this function is the one
-- reviewed crossing (architecture.md section 5, control T5; arch-test TenantFilterRule REVIEWED_CROSSINGS).
--
-- Same narrow pattern as V1_3_3 / V2_3_5 / V2_3_811: owned by the migration owner, search_path pinned, every name
-- schema-qualified (the definer hijack guard), only ids leave it. Numbered in the E11 lane-C band V2_3_1130-1139
-- (E11-plan section 5).
CREATE FUNCTION public.ipaas_active_tenants()
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT t.id
      FROM public.tenant t
     WHERE t.status = 'ACTIVE'
     ORDER BY t.id;
$$;

-- The function is a scanning-job boundary; nobody but the application role gets it.
REVOKE ALL ON FUNCTION public.ipaas_active_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.ipaas_active_tenants() TO ipaas_app;

-- Rollback (manual, owner only; Flyway community has no undo):
--   DROP FUNCTION public.ipaas_active_tenants();
