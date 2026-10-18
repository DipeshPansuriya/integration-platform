-- E12 N3 (T-067 narrow, row278 wave): the append-only audit ledger - one immutable row per audit event
-- (who, what, when, tenant, target ids, before/after), the E12-F2-S1 spine every later lane hooks into.
-- Enforcement mirrors delivery_attempt (V2_3_2, the R15 idiom): the application role gets INSERT and SELECT only,
-- so UPDATE and DELETE fail with 42501; row-level security isolates tenants; the purge (7-year retention, T-135)
-- is a later partition drop, never a row delete.
--
-- Partitioned by MONTH from the FIRST migration (the E12 lane-B plan): the audit is the platform's accountability
-- record and its retention is per partition. The pre-created window covers last month through twelve months
-- forward, the same migration-time window the delivery day partitions use; monthly maintenance beyond it moves
-- onto the E14 scheduler (E12-plan Open 3).

CREATE TABLE public.audit_event (
    id           bigint      GENERATED ALWAYS AS IDENTITY,
    tenant_id    uuid        NOT NULL REFERENCES public.tenant (id),
    actor        text        NOT NULL,
    action       text        NOT NULL,
    target_ids   jsonb       NOT NULL DEFAULT '[]'::jsonb,
    before_state text,
    after_state  text,
    event_at     timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- the activity read (T-069) is newest-first per tenant, filtered by action and date
CREATE INDEX audit_event_tenant_time_idx ON public.audit_event (tenant_id, event_at DESC);

DO $$
DECLARE
    month_start date;
BEGIN
    FOR month_start IN
        SELECT generate_series(
            date_trunc('month', CURRENT_DATE) - interval '1 month',
            date_trunc('month', CURRENT_DATE) + interval '12 months',
            interval '1 month')::date
    LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF public.audit_event FOR VALUES FROM (%L) TO (%L)',
            'audit_event_' || to_char(month_start, 'YYYYMM'), month_start,
            (month_start + interval '1 month')::date);
    END LOOP;
END
$$;

ALTER TABLE public.audit_event ENABLE ROW LEVEL SECURITY;

CREATE POLICY audit_event_tenant_isolation ON public.audit_event
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- R15 in one line: INSERT and SELECT, nothing else - explicitly revoked so a later "GRANT ALL" convenience
-- cannot reopen the ledger (the V1_3_2 idiom, exactly as delivery_attempt).
GRANT SELECT, INSERT ON public.audit_event TO ipaas_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.audit_event FROM ipaas_app;
