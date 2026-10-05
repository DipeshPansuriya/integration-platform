-- E2 lane C (T-012 + T-091): the delivery_attempt ledger - APPEND-ONLY (R15, E2-F2-S1 @negative). Every target
-- call writes one row here and the row is never rewritten: enforcement is by grant (the application role gets
-- INSERT and SELECT only, so UPDATE and DELETE fail with 42501) and every repository method here is an append;
-- the container test proves a code path trying otherwise is refused. layer is ADR-010 section 4 (1 = Camel
-- redelivery, 2 = the delivery-retry job's next attempt); outcome_class is the register of ADR-010 section 5.
--
-- Partitioned by day like the delivery (T-091): attempts are the highest-volume table of the epic and purge is a
-- partition drop. created_at is the insert time and never changes. No foreign key to delivery (a partitioned
-- parent exposes no single-column unique to reference); the ledger insert reads the delivery row under row-level
-- security first, so an attempt can only be appended to a delivery of the caller's tenant.

CREATE TABLE public.delivery_attempt (
    id            bigint      GENERATED ALWAYS AS IDENTITY,
    tenant_id     uuid        NOT NULL REFERENCES public.tenant (id),
    delivery_id   uuid        NOT NULL,
    layer         smallint    NOT NULL CHECK (layer IN (1, 2)),
    outcome_class text        NOT NULL
                CHECK (outcome_class IN ('SUCCESS', 'TRANSIENT', 'AMBIGUOUS', 'PERMANENT')),
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz NOT NULL,
    detail        text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX delivery_attempt_delivery_idx ON public.delivery_attempt (tenant_id, delivery_id, started_at);

DO $$
DECLARE
    day date;
BEGIN
    FOR day IN SELECT generate_series(CURRENT_DATE - 1, CURRENT_DATE + 7, interval '1 day')::date
    LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF public.delivery_attempt FOR VALUES FROM (%L) TO (%L)',
            'delivery_attempt_' || to_char(day, 'YYYYMMDD'), day, day + 1);
    END LOOP;
END
$$;

ALTER TABLE public.delivery_attempt ENABLE ROW LEVEL SECURITY;

CREATE POLICY delivery_attempt_tenant_isolation ON public.delivery_attempt
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- R15 in one line: INSERT and SELECT, nothing else - explicitly revoked so a later "GRANT ALL" convenience
-- cannot reopen the ledger (the V1_3_2 idiom).
GRANT SELECT, INSERT ON public.delivery_attempt TO ipaas_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.delivery_attempt FROM ipaas_app;
