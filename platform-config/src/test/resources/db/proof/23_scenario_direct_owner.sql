-- Lane B proof scenarios (T-020) - the direct-to-database path, run as the migration owner
-- (ipaas_migrator): not the application role, a privileged connection straight at the tables.
-- Single-user workflow (V2_4_1420): the approver_not_author CHECK is gone, so approver = author is accepted here too.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

DO $$
BEGIN
    UPDATE public.interface_version
        SET approver_id = 'b7e8c2d0-aaaa-4000-8000-0000000000a1',
            status = 'APPROVED'
        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001';
    INSERT INTO public.proof_probe VALUES ('t020_direct_owner_self_approval_allowed', 'NONE', 'NONE',
                                           'approver = author accepted (the CHECK is gone)');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t020_direct_owner_self_approval_allowed', 'NONE', SQLSTATE,
                                           left(SQLERRM, 200));
END $$;

-- Even as the owner, the version's content cannot be revised in place (the transition guard).
DO $$
BEGIN
    UPDATE public.interface_version
        SET snapshot = '{"rewritten": true}'::jsonb
        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001';
    INSERT INTO public.proof_probe VALUES ('t018_owner_snapshot_edit_refused', 'P0001', 'NONE',
                                           'refusal did not fire');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_owner_snapshot_edit_refused', 'P0001', SQLSTATE, left(SQLERRM, 200));
END $$;

-- An unsubmitted version cannot jump to APPROVED (the lifecycle is draft, submit, approve, publish).
DO $$
BEGIN
    INSERT INTO public.interface_version (id, tenant_id, interface_id, version, business_key_rule,
                                          source_contract_version_id, author_id, snapshot)
        VALUES ('b7e8c2d0-8888-4000-8000-000000000003', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-5555-4000-8000-000000000001', 3,
                'body.orderId, mandatory + unique per 30 days', 'b7e8c2d0-4444-4000-8000-000000000001',
                'b7e8c2d0-aaaa-4000-8000-0000000000a2', '{"v": 3}'::jsonb);
    UPDATE public.interface_version
        SET status = 'APPROVED', approver_id = 'b7e8c2d0-aaaa-4000-8000-0000000000a3'
        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000003';
    INSERT INTO public.proof_probe VALUES ('t018_skip_lifecycle_refused', 'P0001', 'NONE', 'refusal did not fire');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_skip_lifecycle_refused', 'P0001', SQLSTATE, left(SQLERRM, 200));
END $$;
COMMIT;