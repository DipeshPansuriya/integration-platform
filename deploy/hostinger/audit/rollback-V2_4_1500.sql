CREATE OR REPLACE FUNCTION public.config_approve_version(p_version_id uuid, p_approver_id uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $f$
DECLARE
    v public.interface_version%ROWTYPE;
BEGIN
    SELECT * INTO v FROM public.interface_version
        WHERE id = p_version_id AND tenant_id = current_setting('app.tenant_id')::uuid FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'VERSION_NOT_FOUND';
    ELSIF v.status <> 'PENDING_APPROVAL' THEN
        INSERT INTO public.config_version_refusal (tenant_id, version_id, refusal, actor_id)
            VALUES (v.tenant_id, p_version_id, 'NOT_PENDING_APPROVAL', p_approver_id);
        RETURN 'REFUSED_' || v.status;
    END IF;
    UPDATE public.interface_version SET status = 'APPROVED', approver_id = p_approver_id, decided_at = now()
        WHERE id = p_version_id;
    RETURN 'APPROVED';
END $f$;
CREATE OR REPLACE FUNCTION public.config_approve_contract_version(p_version_id uuid, p_approver_id uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $f$
DECLARE
    v public.contract_version%ROWTYPE;
BEGIN
    SELECT * INTO v FROM public.contract_version
        WHERE id = p_version_id AND tenant_id = current_setting('app.tenant_id')::uuid FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'VERSION_NOT_FOUND';
    ELSIF v.status <> 'PENDING_APPROVAL' THEN
        RETURN 'REFUSED_' || v.status;
    END IF;
    UPDATE public.contract_version SET status = 'APPROVED', approver_id = p_approver_id WHERE id = p_version_id;
    RETURN 'APPROVED';
END $f$;
