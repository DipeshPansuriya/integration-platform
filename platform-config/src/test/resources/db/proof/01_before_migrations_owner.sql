-- Lane B proof harness pre-migration setup (T-014, T-018, T-020) - runs as ipaas_migrator.
-- The probe table records every scenario probe: step, the SQLSTATE the refusal contract wants,
-- and the SQLSTATE that actually fired. The report file turns it into the run's verdict.

CREATE TABLE public.proof_probe (
    step         text PRIMARY KEY,
    want_sqlstate text NOT NULL,
    got_sqlstate text NOT NULL,
    note         text
);

CREATE OR REPLACE FUNCTION public.probe_expect_failure(p_step text, p_want text, p_action text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE p_action;
    INSERT INTO public.proof_probe VALUES (p_step, p_want, 'NONE', 'refusal did not fire');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES (p_step, p_want, SQLSTATE, left(SQLERRM, 200));
END $$;

CREATE OR REPLACE FUNCTION public.probe_expect_success(p_step text, p_action text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE p_action;
    INSERT INTO public.proof_probe VALUES (p_step, 'NONE', 'NONE', 'statement succeeded');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES (p_step, 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

GRANT EXECUTE ON FUNCTION public.probe_expect_failure(text, text, text),
                            public.probe_expect_success(text, text) TO ipaas_app;
GRANT SELECT, INSERT ON public.proof_probe TO ipaas_app;

-- The two tenants the scenarios work across. The owner runs these; the owners of the directory
-- tables bypass row-level security (no FORCE policy exists, V1_3_2).
INSERT INTO public.tenant (id, code, status, time_zone) VALUES
    ('0000000a-0000-4000-8000-00000000000a', 'tenant-a', 'ACTIVE', 'Etc/UTC'),
    ('0000000b-0000-4000-8000-00000000000b', 'tenant-b', 'ACTIVE', 'Etc/UTC');

-- The restricted application login (its NOLOGIN grantee role ipaas_app was created by V1_3_1).
CREATE ROLE ipaas_app_login LOGIN PASSWORD :'app_password' IN ROLE ipaas_app;