-- Lane B proof scenarios (T-020) - the approval attempts, as ipaas_app_login.
-- Single-user workflow (V2_4_1420): the author U1 may approve their own version; the approval names U1. A second
-- approval attempt (by U2) finds the version no longer pending: REFUSED_APPROVED, recorded as NOT_PENDING_APPROVAL.
-- Two-user approval stays proven: U2 approves a pending version U1 authored (second block at the end).
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

-- The author approves their own version (the only granted database path). Row 544 (V2_4_1480): the approver is
-- the session actor the application stamps in the same transaction.
SELECT set_config('app.actor_id', 'b7e8c2d0-aaaa-4000-8000-0000000000a1', true);

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

-- A second user then finds the version already decided (the state check answers first).
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);
SELECT set_config('app.actor_id', 'b7e8c2d0-aaaa-4000-8000-0000000000a2', true);

DO $$
DECLARE
    outcome text;
BEGIN
    outcome := config_approve_version('b7e8c2d0-8888-4000-8000-000000000001',
                                      'b7e8c2d0-aaaa-4000-8000-0000000000a2'::uuid);
    INSERT INTO public.proof_probe VALUES ('t020_second_approval_refused_not_pending', 'REFUSED_APPROVED', outcome,
                                           'already approved: not pending');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t020_second_approval_refused_not_pending', 'REFUSED_APPROVED', SQLSTATE,
                                           left(SQLERRM, 200));
END $$;
COMMIT;

-- Two-user approval: U1 authors and submits another version (2), a DIFFERENT user U2 approves it.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

DO $$
BEGIN
    INSERT INTO public.interface_version (id, tenant_id, interface_id, version, business_key_rule,
                                          source_contract_version_id, author_id, snapshot)
        VALUES ('b7e8c2d0-8888-4000-8000-000000000004', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-5555-4000-8000-000000000001', 2,
                'body.orderId, mandatory + unique per 30 days', 'b7e8c2d0-4444-4000-8000-000000000001',
                'b7e8c2d0-aaaa-4000-8000-0000000000a1', '{"interface": "CTS_TO_ERP_INVOICES", "routes": 2}'::jsonb);
    UPDATE public.interface_version
        SET status = 'PENDING_APPROVAL', change_note = 'second cut, approved by another user'
        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000004';
    INSERT INTO public.proof_probe VALUES ('t020_two_user_version_submitted', 'NONE', 'NONE', 'U1 submitted version 2');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t020_two_user_version_submitted', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

SELECT set_config('app.actor_id', 'b7e8c2d0-aaaa-4000-8000-0000000000a2', true);

DO $$
DECLARE
    outcome text;
BEGIN
    outcome := config_approve_version('b7e8c2d0-8888-4000-8000-000000000004',
                                      'b7e8c2d0-aaaa-4000-8000-0000000000a2'::uuid);
    INSERT INTO public.proof_probe VALUES ('t020_approve_by_other_user', 'APPROVED', outcome,
                                           'U2 approves a pending version authored by U1');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t020_approve_by_other_user', 'APPROVED', SQLSTATE,
                                           left(SQLERRM, 200));
END $$;

DO $$
BEGIN
    IF (SELECT status || '/' || author_id::text || '/' || approver_id::text FROM public.interface_version
            WHERE id = 'b7e8c2d0-8888-4000-8000-000000000004')
           = 'APPROVED/b7e8c2d0-aaaa-4000-8000-0000000000a1/b7e8c2d0-aaaa-4000-8000-0000000000a2' THEN
        INSERT INTO public.proof_probe VALUES ('t020_other_user_approval_recorded', 'NONE', 'NONE',
                                               'APPROVED, author U1, approver U2');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t020_other_user_approval_recorded', 'NONE', 'FAIL',
                                               'status, author or approver wrong');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t020_other_user_approval_recorded', 'NONE', SQLSTATE,
                                           left(SQLERRM, 200));
END $$;
COMMIT;

-- Row 544 (V2_4_1480): no stamped actor is refused, and so is an argument that disagrees with the stamp; neither
-- writes a refusal row or changes the version.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

DO $$
DECLARE
    outcome text;
BEGIN
    outcome := config_approve_version('b7e8c2d0-8888-4000-8000-000000000004',
                                      'b7e8c2d0-aaaa-4000-8000-0000000000a2'::uuid);
    INSERT INTO public.proof_probe VALUES ('r544_no_actor_refused', 'REFUSED_NO_ACTOR', outcome,
                                           'no app.actor_id stamped in this transaction');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('r544_no_actor_refused', 'REFUSED_NO_ACTOR', SQLSTATE, left(SQLERRM, 200));
END $$;

SELECT set_config('app.actor_id', 'b7e8c2d0-aaaa-4000-8000-0000000000a1', true);

DO $$
DECLARE
    outcome text;
BEGIN
    outcome := config_approve_version('b7e8c2d0-8888-4000-8000-000000000004',
                                      'b7e8c2d0-aaaa-4000-8000-0000000000a2'::uuid);
    INSERT INTO public.proof_probe VALUES ('r544_actor_mismatch_refused', 'REFUSED_ACTOR_MISMATCH', outcome,
                                           'stamp names U1, argument names U2');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('r544_actor_mismatch_refused', 'REFUSED_ACTOR_MISMATCH', SQLSTATE,
                                           left(SQLERRM, 200));
END $$;
COMMIT;
