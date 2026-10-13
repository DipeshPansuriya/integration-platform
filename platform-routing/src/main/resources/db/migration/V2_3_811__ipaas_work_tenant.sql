-- E8 lane A (T-111, row 278 wave 1): the protected work-tenant function the stage consumers resolve their
-- tenant through (ADR-011 Decision 8, ADR-014). A Kafka consumer binds the tenant of the row the record names -
-- never from a record header or value (E8-C4, backlog.md:2039-2049) - by calling one trusted definer function
-- that reads the row directly (bypassing RLS as its owner) and returns only the tenant id.
--
-- Numbered after the delivery family (V2_3_: the higher-prefix rule) because it reads public.delivery, which
-- V2_3_1 created. The same narrow pattern as the pre-tenant boundary functions (V1_3_3, V1_3_5): search_path
-- pinned, every name schema-qualified, only the tenant id leaves it.
--
-- Kinds (ADR-014): MESSAGE -> public.message, DELIVERY -> public.delivery. Both are partitioned; the by-id read
-- walks the kept partitions of the family, which ADR-014 accepts (~38 index probes at 50 msg/s).

CREATE OR REPLACE FUNCTION public.ipaas_work_tenant(p_kind text, p_id uuid)
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT (CASE p_kind
                WHEN 'MESSAGE'  THEN (SELECT m.tenant_id FROM public.message  m WHERE m.id = p_id LIMIT 1)
                WHEN 'DELIVERY' THEN (SELECT d.tenant_id FROM public.delivery d WHERE d.id = p_id LIMIT 1)
            END);
$$;

-- The function is a stage-consumer boundary; nobody but the application role gets it.
REVOKE ALL ON FUNCTION public.ipaas_work_tenant(text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.ipaas_work_tenant(text, uuid) TO ipaas_app;

-- Rollback (manual, owner only; Flyway community has no undo):
--   DROP FUNCTION public.ipaas_work_tenant(text, uuid);