-- E2 T-010 (amit-developer, ledger row 278): the consumer-side duplicate guard's storage (ADR-001; E2-F1-S1: a crash
-- after publish and before the mark re-publishes the SAME outbox row, so the receiving side detects the duplicate by
-- OutboundRecord.idempotencyKey() = "outbox:<row-id>"; architecture.md section 3 "Intake" idempotency_key line).
--
-- Fixed contract, same as the outbox seam: columns may be ADDED later by a lane's own migration (nullable or
-- defaulted, V2_<lane>_<n>) - the intake usage from architecture.md (source credential, request-body hash, results,
-- 24-hour retention) extends this table additively; never rename or drop. Closed shapes are text + CHECK, never
-- ENUM (R10).

CREATE TABLE public.idempotency_key (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES public.tenant (id),
    idempotency_key text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 512),
    first_seen_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_key_per_tenant UNIQUE (tenant_id, idempotency_key)
);

-- Row-level security, error-deny form (ADR-009 Decision 5), as V1_3_2 and V2_0_1: an unset setting fails the
-- statement; claims are visible and writable only for the bound tenant (the unique key is per tenant, so two
-- tenants' identical keys never collide - no cross-tenant signal, R08).
ALTER TABLE public.idempotency_key ENABLE ROW LEVEL SECURITY;

CREATE POLICY idempotency_key_tenant_isolation ON public.idempotency_key
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- A consumer claims keys and reads them; the history of what was consumed is never rewritten or erased by the
-- application role - duplicate detection is only as trustworthy as that.
GRANT SELECT, INSERT ON public.idempotency_key TO ipaas_app;
