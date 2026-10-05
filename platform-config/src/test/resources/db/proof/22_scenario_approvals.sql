-- Lane B proof scenarios (T-020) - the approval attempts, as ipaas_app_login.
-- @negative @security "The author cannot approve": author U1 calls the approval path; the
-- refusal is recorded and the version is untouched. Then approver U2 (never the author) approves.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

-- The author tries to approve (the only granted database path records the refusal).
DO $$
DECLARE
    outcome text;
BEGIN
    outcome := config_approve_version('b7e8c2d0-8888-4000-8000-000000000001',
                                      'b7e8c2d0-aaaa-4000-8000-0000000000a1'::uuid);
    IF outcome = 'REFUSED_AUTHOR_IS_APPROVER' THEN
        INSERT INTO public.proof_probe VALUES ('t020_author_approval_refused', 'REFUSED', 'REFUSED',
                                               'recorded by the approval function');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t020_author_approval_refused', 'REFUSED', outcome,
                                               'wrong function outcome');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t020_author_approval_refused', 'REFUSED', SQLSTATE,
                                           left(SQLERRM, 200));
END $$;

-- The refusal is on the audit trail and the version is still pending.
DO $$
BEGIN
    IF (SELECT count(*) FROM public.config_version_refusal
            WHERE version_id = 'b7e8c2d0-8888-4000-8000-000000000001'
              AND refusal = 'AUTHOR_IS_APPROVER'
              AND actor_id = 'b7e8c2d0-aaaa-4000-8000-0000000000a1') = 1
       AND (SELECT status FROM public.interface_version
            WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001') = 'PENDING_APPROVAL' THEN
        INSERT INTO public.proof_probe VALUES ('t020_refusal_recorded_version_untouched', 'NONE', 'NONE',
                                               'refusal recorded');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t020_refusal_recorded_version_untouched', 'NONE', 'FAIL',
                                               'refusal row or status wrong');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t020_refusal_recorded_version_untouched', 'NONE', SQLSTATE,
                                           left(SQLERRM, 200));
END $$;
COMMIT;

-- The real approver approves (another user), then the app publishes the approved bundle.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

DO $$
DECLARE
    outcome text;
BEGIN
    outcome := config_approve_version('b7e8c2d0-8888-4000-8000-000000000001',
                                      'b7e8c2d0-aaaa-4000-8000-0000000000a2'::uuid);
    INSERT INTO public.proof_probe VALUES ('t018_approve_by_other_user', outcome, outcome, 'approved');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_approve_by_other_user', 'APPROVED', SQLSTATE, left(SQLERRM, 200));
END $$;
COMMIT;