-- T-138 (E13-F1-S2): the daily delivery pre-aggregate, one row per tenant / interface / target / day. Rebuilt by the
-- DAILY_REPORT job (delete + insert of one day, per tenant); a direct count over delivery + delivery_attempt is the
-- reconciliation check. Same tenant isolation as the other tenant tables: ENABLE + FORCE, the owner included.
CREATE TABLE public.delivery_daily_aggregate (
    tenant_id              uuid        NOT NULL REFERENCES public.tenant (id),
    day                    date        NOT NULL,
    interface_id           uuid,
    target_id              uuid,
    successes              bigint      NOT NULL DEFAULT 0,
    failures_transient     bigint      NOT NULL DEFAULT 0,
    failures_ambiguous     bigint      NOT NULL DEFAULT 0,
    failures_permanent     bigint      NOT NULL DEFAULT 0,
    retries_made           bigint      NOT NULL DEFAULT 0,
    retried_then_succeeded bigint      NOT NULL DEFAULT 0,
    still_stuck            bigint      NOT NULL DEFAULT 0,
    refreshed_at           timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX delivery_daily_aggregate_key ON public.delivery_daily_aggregate (tenant_id, day,
    coalesce(interface_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(target_id, '00000000-0000-0000-0000-000000000000'::uuid));

ALTER TABLE public.delivery_daily_aggregate ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.delivery_daily_aggregate FORCE ROW LEVEL SECURITY;

CREATE POLICY delivery_daily_aggregate_tenant_isolation ON public.delivery_daily_aggregate
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- a refresh replaces a day (delete + insert); rows are never edited in place
GRANT SELECT, INSERT, DELETE ON public.delivery_daily_aggregate TO ipaas_app;
REVOKE UPDATE, TRUNCATE ON public.delivery_daily_aggregate FROM ipaas_app;
