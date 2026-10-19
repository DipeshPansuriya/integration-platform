-- E11 T-187 (T115): intake Idempotency-Key replay store, scoped per tenant AND source credential.
-- request_hash refuses a reused key with another body (422 IDEMPOTENCY_KEY_REUSED); results replays the
-- original per-record answers; rows live 24 h (expires_at) and are purged on intake. Separate from
-- public.idempotency_key, which is the consumer-side DuplicateGuard's ("outbox:<id>" keys).
-- Rollback: DROP TABLE public.intake_idempotency;
CREATE TABLE public.intake_idempotency (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES public.tenant (id),
    credential_ref  text        NOT NULL CHECK (length(credential_ref) <= 512),
    idempotency_key text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 512),
    request_hash    text        NOT NULL CHECK (length(request_hash) = 64),
    results         jsonb       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    CONSTRAINT intake_idempotency_per_credential UNIQUE (tenant_id, credential_ref, idempotency_key)
);

CREATE INDEX intake_idempotency_expiry ON public.intake_idempotency (tenant_id, expires_at);

ALTER TABLE public.intake_idempotency ENABLE ROW LEVEL SECURITY;

CREATE POLICY intake_idempotency_tenant_isolation ON public.intake_idempotency
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

GRANT SELECT, INSERT, DELETE ON public.intake_idempotency TO ipaas_app;
