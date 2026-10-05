-- E2 lane C (T-012, T-193): the delivery_event log - every transition with its time (E2-F2-S1), and the named
-- deferrals of ADR-010 sections 5 to 7: the claim (CLAIM), the hold and its release (HELD, RELEASE), the retry
-- re-drive (RETRY, REDRIVE), the final states (DELIVER, DEAD_LETTER), the operator replay (REPLAY), the
-- no-call deferrals (BULKHEAD_FULL, KEY_UNAVAILABLE, CIRCUIT_DEFER, RECONCILE) and the stale or foreign claim
-- outcome (LATE_OUTCOME, ADR-010 section 6: "it writes ... a LATE_OUTCOME delivery event"). Append-only like
-- the attempts (R15 in spirit); not partitioned - a delivery carries a handful of events, the attempts carry the
-- volume. The type register mirrors DeliveryEventType (R10: text + CHECK).

CREATE TABLE public.delivery_event (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES public.tenant (id),
    delivery_id uuid        NOT NULL,
    type        text        NOT NULL
              CHECK (type IN ('CLAIM', 'HELD', 'RELEASE', 'RETRY', 'REDRIVE', 'DELIVER', 'DEAD_LETTER',
                              'REPLAY', 'BULKHEAD_FULL', 'KEY_UNAVAILABLE', 'CIRCUIT_DEFER', 'RECONCILE',
                              'LATE_OUTCOME')),
    detail      text,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX delivery_event_delivery_idx ON public.delivery_event (tenant_id, delivery_id, occurred_at);

ALTER TABLE public.delivery_event ENABLE ROW LEVEL SECURITY;

CREATE POLICY delivery_event_tenant_isolation ON public.delivery_event
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

GRANT SELECT, INSERT ON public.delivery_event TO ipaas_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.delivery_event FROM ipaas_app;
