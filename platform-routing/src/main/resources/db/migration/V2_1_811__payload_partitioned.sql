-- E8 lane A (T-111, row 278 wave 1): the public.payload table - the message body (jsonb) of ADR-014 Decision 1,
-- its own day-partitioned table so a purge drops one day of message and payload together. accepted_at is copied
-- from its message at insert: the payload always sits in its message's day-partition, which is what makes the
-- ADR-011 purge guard (one count on one delivery partition) sufficient. No FK into message - the partitioned
-- parent cannot expose a single-column unique to reference (as delivery_attempt, V2_3_2); RLS plus the insert
-- path (intake writes message and payload in one transaction, architecture.md:98) enforce the link.
--
-- Row-level security in the same error-deny form as message (ADR-009 Decision 5); NOT FORCE, like message -
-- the maintenance/purge identity sees the table family as it sees delivery.

CREATE TABLE public.payload (
    message_id  uuid        NOT NULL,
    tenant_id   uuid        NOT NULL REFERENCES public.tenant (id),
    accepted_at timestamptz NOT NULL,
    body        jsonb       NOT NULL,
    PRIMARY KEY (message_id, accepted_at)
) PARTITION BY RANGE (accepted_at);

DO $$
DECLARE
    day date;
BEGIN
    FOR day IN SELECT generate_series(CURRENT_DATE - 1, CURRENT_DATE + 7, interval '1 day')::date
    LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF public.payload FOR VALUES FROM (%L) TO (%L)',
            'payload_' || to_char(day, 'YYYYMMDD'), day, day + 1);
    END LOOP;
END
$$;

ALTER TABLE public.payload ENABLE ROW LEVEL SECURITY;

CREATE POLICY payload_tenant_isolation ON public.payload
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- The application reads and appends the payload; it may never change or remove one (ADR-014 conformance test 3:
-- UPDATE/DELETE as ipaas_app fail with 42501). Purge is the maintenance role's partition drop.
GRANT SELECT, INSERT ON public.payload TO ipaas_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.payload FROM ipaas_app;

-- Rollback (manual, owner only; Flyway community has no undo): DROP TABLE public.payload;