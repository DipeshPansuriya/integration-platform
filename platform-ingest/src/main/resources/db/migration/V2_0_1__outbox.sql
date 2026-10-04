-- E2 seam (arjun-fullstack, ledger row 278): the transactional outbox table (ADR-001, ADR-005 Decisions 9 and 11,
-- architecture.md section 3 "Intake" and 4.1 step 6). Written before the lanes start because three E2 lanes build on
-- it: lane A writes rows (T-007), lane B claims, publishes and marks them (T-008), lane D kills the relay (T-009).
--
-- Fixed contract: table and column names below; more columns may be ADDED later by a lane's own migration
-- (nullable or defaulted, V2_<lane>_<n>), never renamed or dropped. Closed sets are text + CHECK, never ENUM (R10).
-- Records carry ids only (ADR-011 Decision 7): no payload column here.

CREATE TABLE public.outbox (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES public.tenant (id),
    topic        text        NOT NULL CHECK (topic IN ('platform.message.accepted', 'platform.delivery.due')),
    aggregate_id uuid        NOT NULL,
    record_key   text        NOT NULL,
    key_bucket   smallint    NOT NULL CHECK (key_bucket BETWEEN 0 AND 63),
    traceparent  text,
    available_at timestamptz NOT NULL DEFAULT now(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

-- The relay's scan: unpublished rows of one tenant and bucket, in outbox-id order (ADR-005 Decision 11).
CREATE INDEX outbox_unpublished_idx ON public.outbox (tenant_id, key_bucket, id) WHERE published_at IS NULL;

-- Row-level security, error-deny form (ADR-009 Decision 5), as V1_3_2: an unset setting fails the statement.
ALTER TABLE public.outbox ENABLE ROW LEVEL SECURITY;

CREATE POLICY outbox_tenant_isolation ON public.outbox
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- The application role appends rows and marks them published; it never deletes or rewrites one.
GRANT SELECT, INSERT ON public.outbox TO ipaas_app;
GRANT UPDATE (published_at) ON public.outbox TO ipaas_app;
