-- E2 lane C (T-091 + T-012, E2-F2-S2): the delivery, PARTITION BY DAY from its first migration so a purge is a
-- partition drop - a momentary DDL that rewrites no kept rows (never a row-by-row DELETE scan of the live
-- table). PostgreSQL queues the drop behind any open transaction whose locks touch the table family (the DDL
-- takes the parent's access-exclusive lock), so the maintenance identity runs it and the queue is its concern. Closed sets are text + CHECK, never ENUM (R10); the
-- state values are exactly the register of ADR-010 section 6 (DeliveryState, seam).
--
-- Partition key: created_at. A row's created_at never changes (a delivery ages in place through RETRY_WAIT and
-- HELD), so every later UPDATE stays inside the row's own partition; the day partition itself is what purge
-- drops. The primary key carries the partition key as PostgreSQL requires - id is a uuid, so it stays unique in
-- practice, and the child tables reference delivery_id WITHOUT a foreign key for the same reason (a partitioned
-- parent cannot expose a single-column unique to reference); tenant row-level security plus the ledger's own
-- insert path (which reads the delivery row first) keep the link enforced.
--
-- Columns added by later phases (message_id, route_id, contract versions) are ADD-only migrations per the seam
-- rule; they are not invented here. Ordering fields follow ADR-011 Decision 6: key_seq is the message's per-key
-- sequence the claim compares when it evaluates the hold (NULL when the interface's ordering is NONE), and
-- (interface_id, business_key, target_id) is the scope the earlier-delivery clause compares; interface and
-- endpoint tables land in E3/E4, so these are plain values for now.
--
-- T6 (architecture.md control table): partition maintenance runs as a separate identity, never the application
-- role - the pre-created days here are the migration owner's, and the future-day maintenance belongs to the
-- later purge/maintenance epic.

CREATE TABLE public.delivery (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES public.tenant (id),
    state              text        NOT NULL
                     CHECK (state IN ('PENDING', 'HELD', 'SENDING', 'RETRY_WAIT', 'DUE', 'DELIVERED',
                                       'DEAD_LETTERED')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    next_attempt_at    timestamptz,
    claim_token        uuid,
    claimed_at         timestamptz,
    dead_letter_class  text
                     CHECK (dead_letter_class IN ('SUCCESS', 'TRANSIENT', 'AMBIGUOUS', 'PERMANENT')),
    bulkhead_deferrals integer     NOT NULL DEFAULT 0,
    interface_id       uuid,
    business_key       text,
    key_seq            bigint,
    target_id          uuid,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- The delivery-retry job's scan (DeliveryRetryStore.lockDue): waiting rows by oldest retry time, per tenant.
CREATE INDEX delivery_retry_due_idx ON public.delivery (tenant_id, next_attempt_at)
    WHERE state = 'RETRY_WAIT';
-- The claim's hold check (ADR-011 Decision 6): earlier non-final deliveries of the same scope.
CREATE INDEX delivery_ordering_idx ON public.delivery (tenant_id, interface_id, business_key, target_id, key_seq);

-- The days around the first deployment; the maintenance identity of T6 keeps the horizon extended later.
DO $$
DECLARE
    day date;
BEGIN
    FOR day IN SELECT generate_series(CURRENT_DATE - 1, CURRENT_DATE + 7, interval '1 day')::date
    LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF public.delivery FOR VALUES FROM (%L) TO (%L)',
            'delivery_' || to_char(day, 'YYYYMMDD'), day, day + 1);
    END LOOP;
END
$$;

-- Row-level security, error-deny form (ADR-009 Decision 5) as V1_3_2/V2_0_1: an unset app.tenant_id fails the
-- statement instead of returning foreign rows. WITH CHECK too - the application may only write its own tenant.
ALTER TABLE public.delivery ENABLE ROW LEVEL SECURITY;

CREATE POLICY delivery_tenant_isolation ON public.delivery
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- The application changes state (never deletes - purging is the partition drop of T-091).
GRANT SELECT, INSERT, UPDATE ON public.delivery TO ipaas_app;
REVOKE DELETE, TRUNCATE ON public.delivery FROM ipaas_app;
