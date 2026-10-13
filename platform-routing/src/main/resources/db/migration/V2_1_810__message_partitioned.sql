-- E8 lane A (T-111, row 278 wave 1): the public.message table - the day-partitioned store the stage consumers
-- load their work row through (ADR-014 Decision 1, approved by Dipesh 2026-10-12). The shape is architecture.md:78:
-- interface version, source contract version, business key, per-key sequence, the ACCEPTED/ROUTED/ROUTE_FAILED
-- state and the stage timestamps. Closed sets are text + CHECK, never ENUM (R10). The partition key is
-- accepted_at, so a purge is a partition drop (never a row-by-row DELETE scan; the exact pattern V2_3_1 rejects),
-- and PostgreSQL routes by-id lookups through the parent - the child partitions are the maintenance role's.
--
-- The tenant_id foreign key into public.tenant is the only FK: like the delivery family, the partitioned parent
-- cannot expose a single-column unique for others to reference; RLS + the insert paths enforce the rest.
-- Row-level security is ENABLE with an error-deny policy (ADR-009 Decision 5, as V2_0_1/V2_3_1) and NOT FORCE:
-- the owner-run maintenance and the ADR-011 Decision 8 purge guard must see this table family the same way they
-- see delivery (ADR-014).

CREATE TABLE public.message (
    id                         uuid        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id                  uuid        NOT NULL REFERENCES public.tenant (id),
    interface_id               uuid        NOT NULL,
    interface_version_id       uuid        NOT NULL,
    source_contract_version_id uuid        NOT NULL,
    business_key               text        NOT NULL CHECK (btrim(business_key) <> ''),
    key_seq                    bigint,
    state                      text        NOT NULL DEFAULT 'ACCEPTED'
                                           CHECK (state IN ('ACCEPTED', 'ROUTED', 'ROUTE_FAILED')),
    accepted_at                timestamptz NOT NULL DEFAULT now(),
    routed_at                  timestamptz,
    updated_at                 timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (id, accepted_at)
) PARTITION BY RANGE (accepted_at);

-- The reconciler's scan (architecture.md:107): ACCEPTED older than 5 minutes, per tenant.
CREATE INDEX message_unrouted_idx ON public.message (tenant_id, accepted_at) WHERE state = 'ACCEPTED';
-- The stage-1 hold (ADR-011 Decision 6 clause 1): an earlier same-key message still ACCEPTED.
CREATE INDEX message_ordering_idx ON public.message (tenant_id, interface_id, business_key, key_seq)
    WHERE state = 'ACCEPTED';

-- The days around the first deployment, as V2_3_1:51-63; the horizon is kept extended by the ADR-012 maintenance
-- job (a DDL identity, never the application role, T6). No DEFAULT partition - one would block later
-- CREATE ... PARTITION OF for any range it holds rows for (ADR-014).
DO $$
DECLARE
    day date;
BEGIN
    FOR day IN SELECT generate_series(CURRENT_DATE - 1, CURRENT_DATE + 7, interval '1 day')::date
    LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF public.message FOR VALUES FROM (%L) TO (%L)',
            'message_' || to_char(day, 'YYYYMMDD'), day, day + 1);
    END LOOP;
END
$$;

ALTER TABLE public.message ENABLE ROW LEVEL SECURITY;

CREATE POLICY message_tenant_isolation ON public.message
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- The application writes rows (intake) and changes state (stage 1); it never deletes - purging is the
-- partition drop of ADR-014 Decision 3, owned by the maintenance role.
GRANT SELECT, INSERT ON public.message TO ipaas_app;
GRANT UPDATE (state, routed_at, updated_at) ON public.message TO ipaas_app;
REVOKE DELETE, TRUNCATE ON public.message FROM ipaas_app;

-- Rollback (manual, owner only; Flyway community has no undo): DROP TABLE public.message;