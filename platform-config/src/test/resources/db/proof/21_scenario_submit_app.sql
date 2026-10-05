-- Lane B proof scenarios (T-018) - submit as ipaas_app_login, then prove the immutability contract:
-- a submitted version cannot be edited (42501 on every content column) and cannot walk backwards.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

-- The digest of the snapshot as it stands at submit time, stashed for the byte-for-byte check.
DO $$
BEGIN
    INSERT INTO public.proof_probe (step, want_sqlstate, got_sqlstate, note)
    SELECT 't018_snapshot_md5_at_submit', 'NONE', 'NONE', md5(snapshot::text)
    FROM public.interface_version WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001';
    IF NOT FOUND THEN
        INSERT INTO public.proof_probe VALUES ('t018_snapshot_md5_at_submit', 'NONE', 'FAIL',
                                               'draft version row absent');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_snapshot_md5_at_submit', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

-- The lifecycle: submit (a new draft version is the only way to change a submitted one).
DO $$
DECLARE r integer;
BEGIN
    UPDATE public.interface_version
        SET status = 'PENDING_APPROVAL', change_note = 'first cut of the CTS to ERP invoices flow'
        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001';
    GET DIAGNOSTICS r = ROW_COUNT;
    IF r = 1 THEN
        INSERT INTO public.proof_probe VALUES ('t018_submit', 'NONE', 'NONE', 'submitted');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t018_submit', 'NONE', 'FAIL', '0 rows updated');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_submit', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;
COMMIT;

-- @negative T-018 "A submitted version cannot be edited" - every content column, same refusal.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

DO $$
BEGIN
    UPDATE public.interface_version
        SET snapshot = '{"tampered": true}'::jsonb
        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001';
    INSERT INTO public.proof_probe VALUES ('t018_snapshot_edit_refused', '42501', 'NONE', 'refusal did not fire');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_snapshot_edit_refused', '42501', SQLSTATE, left(SQLERRM, 200));
END $$;

DO $$
BEGIN
    UPDATE public.interface_version
        SET business_key_rule = 'body.custRef'
        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001';
    INSERT INTO public.proof_probe VALUES ('t018_rule_edit_refused', '42501', 'NONE', 'refusal did not fire');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_rule_edit_refused', '42501', SQLSTATE, left(SQLERRM, 200));
END $$;

DO $$
BEGIN
    UPDATE public.interface_version
        SET author_id = 'b7e8c2d0-aaaa-4000-8000-0000000000a2'
        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001';
    INSERT INTO public.proof_probe VALUES ('t018_author_edit_refused', '42501', 'NONE', 'refusal did not fire');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_author_edit_refused', '42501', SQLSTATE, left(SQLERRM, 200));
END $$;

-- A submitted version cannot walk backwards to DRAFT either (a new draft version is the only way).
DO $$
BEGIN
    UPDATE public.interface_version
        SET status = 'DRAFT'
        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001';
    INSERT INTO public.proof_probe VALUES ('t018_backward_transition_refused', 'P0001', 'NONE',
                                           'refusal did not fire');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_backward_transition_refused', 'P0001', SQLSTATE,
                                           left(SQLERRM, 200));
END $$;
COMMIT;