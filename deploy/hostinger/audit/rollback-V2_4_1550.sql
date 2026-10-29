DROP TRIGGER IF EXISTS interface_version_force_draft_insert ON public.interface_version;
DROP FUNCTION IF EXISTS public.force_interface_version_draft();
GRANT INSERT (status, approver_id, decided_at, submitted_at, published_at) ON public.interface_version TO ipaas_app;
REVOKE INSERT (tenant_id, interface_id, version, business_key_rule, snapshot, source_contract_version_id, author_id) ON public.interface_version FROM ipaas_app;
GRANT SELECT, INSERT ON public.interface_version TO ipaas_app;
ALTER TABLE public.interface_version ALTER COLUMN status DROP DEFAULT;
