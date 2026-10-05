-- Lane B proof scenarios (T-018) - the publish step, as ipaas_app_login: the approved version is
-- published and the interface points at it. The row's snapshot columns were never touched between
-- submit and publish (proved byte for byte in the verification file).
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

DO $$
DECLARE r integer;
BEGIN
    UPDATE public.interface_version
        SET status = 'PUBLISHED'
        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001';
    UPDATE public.interface
        SET published_version = 1, status = 'PUBLISHED'
        WHERE id = 'b7e8c2d0-5555-4000-8000-000000000001';
    GET DIAGNOSTICS r = ROW_COUNT;
    IF r = 1 THEN
        INSERT INTO public.proof_probe VALUES ('t018_publish', 'NONE', 'NONE', 'published');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t018_publish', 'NONE', 'FAIL', 'interface row not updated');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_publish', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;
COMMIT;

-- After publish, an attempt to move a published version back to APPROVED is refused too.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

DO $$
BEGIN
    UPDATE public.interface_version
        SET status = 'APPROVED'
        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001';
    INSERT INTO public.proof_probe VALUES ('t018_unpublish_refused', 'P0001', 'NONE', 'refusal did not fire');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_unpublish_refused', 'P0001', SQLSTATE, left(SQLERRM, 200));
END $$;
COMMIT;