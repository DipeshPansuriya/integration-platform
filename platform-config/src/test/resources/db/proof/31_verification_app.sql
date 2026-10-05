-- Lane B proof verification (T-014) - the row-level-security behavior half, run as
-- ipaas_app_login. Another tenant's rows are invisible (backlog E3-F1-S1 @tenant scenario) and an
-- unstamped session fails closed (the uuid cast of the unset setting, ADR-009 error-deny form).
BEGIN;
SELECT set_config('app.tenant_id', '0000000b-0000-4000-8000-00000000000b', true);

DO $$
BEGIN
    IF (SELECT count(*) FROM public.system) = 0
       AND (SELECT count(*) FROM public.interface_version) = 0
       AND (SELECT count(*) FROM public.config_version_refusal) = 0 THEN
        INSERT INTO public.proof_probe VALUES ('t014_other_tenant_invisible', 'NONE', 'NONE', 'no rows');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t014_other_tenant_invisible', 'NONE', 'FAIL',
                                               'tenant A rows visible to tenant B');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_other_tenant_invisible', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

DO $$
BEGIN
    IF (SELECT count(*) FROM public.system
            WHERE tenant_id = '0000000a-0000-4000-8000-00000000000a') = 0 THEN
        INSERT INTO public.proof_probe VALUES ('t014_cross_tenant_probe_refused', 'NONE', 'NONE',
                                               'row predicate keeps working');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t014_cross_tenant_probe_refused', 'NONE', 'FAIL',
                                               'predicate bypassed');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_cross_tenant_probe_refused', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;
COMMIT;

-- An unstamped application session must fail closed. Autocommit: the stamps above were cleared
-- with their transactions, so this statement carries no setting at all.
DO $$
BEGIN
    PERFORM count(*) FROM public.system;
    INSERT INTO public.proof_probe VALUES ('t014_unstamped_fails_closed', 'ERR', 'NONE',
                                           'read succeeded without a stamp');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_unstamped_fails_closed', 'ERR', SQLSTATE, left(SQLERRM, 200));
END $$;