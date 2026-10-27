-- Lane B proof scenarios (T-020) - the approval attempts, as ipaas_app_login.
-- Single-user workflow (V2_4_1420): the author U1 may approve their own version; the approval names U1. A second
-- approval attempt (by U2) finds the version no longer pending: REFUSED_APPROVED, recorded as NOT_PENDING_APPROVAL.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

-- The author approves their own version (the only granted database path).
DO $$
DECLARE
    outcome text;
BEGIN
    outcome := config_approve_version('b7e8c2d0-8888-4000-8000-000000000001',
                                      'b7e8c2d0-aaaa-4000-8000-0000000000a1'::uuid);
    INSERT INTO public.proof_probe VALUES ('t020_author_approval_allowed', 'APPROVED', outcome,
                                           'the author approves their own version');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t020_author_approval_allowed', 'APPROVED', SQLSTATE,
                                           left(SQLERRM, 200));
END $$;

-- The version is APPROVED, names the author as approver, and no refusal row was written.
DO $$
BEGIN
    IF (SELECT count(*) FROM public.config_version_refusal
            WHERE version_id = 'b7e8c2d0-8888-4000-8000-000000000001') = 0
       AND (SELECT status || '/' || approver_id::text FROM public.interface_version
            WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001')
           = 'APPROVED/b7e8c2d0-aaaa-4000-8000-0000000000a1' THEN
        INSERT INTO public.proof_probe VALUES ('t020_self_approval_recorded_on_the_version', 'NONE', 'NONE',
                                               'approved by the author, no refusal row');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t020_self_approval_recorded_on_the_version', 'NONE', 'FAIL',
                                               'status, approver or refusal rows wrong');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t020_self_approval_recorded_on_the_version', 'NONE', SQLSTATE,
                                           left(SQLERRM, 200));
END $$;
COMMIT;

-- A second user then finds the version already decided (the state check answers first), then the app publishes.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

DO $$
DECLARE
    outcome text;
BEGIN
    outcome := config_approve_version('b7e8c2d0-8888-4000-8000-000000000001',
                                      'b7e8c2d0-aaaa-4000-8000-0000000000a2'::uuid);
    INSERT INTO public.proof_probe VALUES ('t018_approve_by_other_user', 'REFUSED_APPROVED', outcome,
                                           'already approved: not pending');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_approve_by_other_user', 'REFUSED_APPROVED', SQLSTATE,
                                           left(SQLERRM, 200));
END $$;
COMMIT;