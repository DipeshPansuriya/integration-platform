-- Row 576 residual: narrowed INSERT privileges and forced DRAFT.
-- ROLLBACK (Flyway Community has no undo; the rollback test runs exactly these lines):
-- ROLLBACK-BEGIN
-- DROP TRIGGER IF EXISTS interface_version_force_draft_insert ON public.interface_version;
-- DROP FUNCTION IF EXISTS public.force_interface_version_draft();
-- GRANT INSERT (status, approver_id, decided_at, submitted_at, published_at) ON public.interface_version TO ipaas_app;
-- REVOKE INSERT (tenant_id, interface_id, version, business_key_rule, snapshot, source_contract_version_id, author_id) ON public.interface_version FROM ipaas_app;
-- GRANT SELECT, INSERT ON public.interface_version TO ipaas_app;
-- ALTER TABLE public.interface_version ALTER COLUMN status DROP DEFAULT;
-- ROLLBACK-END

ALTER TABLE public.interface_version ALTER COLUMN status SET DEFAULT 'DRAFT';

REVOKE INSERT ON public.interface_version FROM ipaas_app;
GRANT INSERT (tenant_id, interface_id, version, business_key_rule, snapshot, source_contract_version_id, author_id) ON public.interface_version TO ipaas_app;

CREATE OR REPLACE FUNCTION public.force_interface_version_draft()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user = 'ipaas_app' THEN
        NEW.status := 'DRAFT';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER interface_version_force_draft_insert
    BEFORE INSERT ON public.interface_version
    FOR EACH ROW
    EXECUTE FUNCTION public.force_interface_version_draft();
