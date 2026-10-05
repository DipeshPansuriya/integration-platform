-- Lane B proof verification (T-014, T-018) - the introspection half, run as ipaas_migrator.
--   1. byte for byte: the published snapshot equals the one stashed at submit (T-018 lifecycle);
--   2. the grants are exactly narrow (the outbox pattern, expanded): content columns of the version
--      tables carry no UPDATE grant for ipaas_app, no table has DELETE/TRUNCATE;
--   3. the tenant-isolation policy and the single-primary invariant index exist on every table.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

DO $$
DECLARE
    at_submit text;
    at_publish text;
BEGIN
    SELECT note INTO at_submit FROM public.proof_probe WHERE step = 't018_snapshot_md5_at_submit';
    SELECT md5(snapshot::text) INTO at_publish FROM public.interface_version
             WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001';
    IF at_submit = at_publish THEN
        INSERT INTO public.proof_probe VALUES ('t018_snapshot_byte_for_byte', 'NONE', 'NONE', 'equal');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t018_snapshot_byte_for_byte', 'NONE', 'FAIL',
                                               at_submit || ' <> ' || at_publish);
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_snapshot_byte_for_byte', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;
COMMIT;

DO $$
BEGIN
    IF (SELECT count(*) FROM information_schema.column_privileges
            WHERE grantee = 'ipaas_app' AND table_schema = 'public'
              AND table_name = 'interface_version' AND privilege_type = 'UPDATE'
              AND column_name IN ('snapshot', 'business_key_rule', 'source_contract_version_id',
                                  'author_id', 'version', 'interface_id')) > 0
       OR (SELECT count(*) FROM information_schema.column_privileges
            WHERE grantee = 'ipaas_app' AND table_schema = 'public'
              AND table_name = 'contract_version' AND privilege_type = 'UPDATE') > 0
       OR (SELECT count(*) FROM information_schema.column_privileges
            WHERE grantee = 'ipaas_app' AND table_schema = 'public'
              AND table_name IN ('system', 'target_endpoint', 'interface', 'route', 'mapping',
                                 'contract', 'interface_version')
              AND privilege_type IN ('DELETE', 'TRUNCATE')) > 0 THEN
        INSERT INTO public.proof_probe VALUES ('t018_grants_narrow', 'NONE', 'FAIL', 'grant set is too wide');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t018_grants_narrow', 'NONE', 'NONE', 'updateable set as designed');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t018_grants_narrow', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

DO $$
DECLARE
    table_name text;
BEGIN
    FOR table_name IN
        SELECT unnest(ARRAY['system', 'target_endpoint', 'contract', 'contract_version',
                            'interface', 'route', 'mapping', 'interface_version']) AS t
    LOOP
        IF (SELECT count(*) FROM pg_policies
                WHERE schemaname = 'public' AND tablename = table_name
                  AND policyname = table_name || '_tenant_isolation'
                  AND qual::text LIKE '%app.tenant_id%') <> 1 THEN
            INSERT INTO public.proof_probe VALUES ('t014_rls_policies_present', 'NONE', 'FAIL',
                                                   'missing policy on ' || table_name);
            RETURN;
        END IF;
    END LOOP;
    INSERT INTO public.proof_probe VALUES ('t014_rls_policies_present', 'NONE', 'NONE', 'all tables');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_rls_policies_present', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

DO $$
BEGIN
    IF (SELECT count(*) FROM pg_indexes
            WHERE schemaname = 'public' AND indexname = 'route_single_primary_idx') = 1 THEN
        INSERT INTO public.proof_probe VALUES ('t014_route_single_primary_index', 'NONE', 'NONE', 'present');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t014_route_single_primary_index', 'NONE', 'FAIL',
                                               'single-primary invariant index missing');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_route_single_primary_index', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;