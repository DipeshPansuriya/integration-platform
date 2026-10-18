-- row278 M2: V2_2_1220 only ENABLEd row level security on the audit ledger, which exempts the table owner.
-- FORCE puts every role, the owner included, under audit_event_tenant_isolation.
ALTER TABLE public.audit_event FORCE ROW LEVEL SECURITY;

-- row278 D2/M3: the transactional audit outbox. The writer appends here INSIDE the business transaction
-- (BEFORE_COMMIT), so an audit row commits or rolls back with the business; the relay moves pending rows into the
-- append-only ledger (audit_event) per tenant and marks them relayed. Same tenant isolation as the ledger.
CREATE TABLE public.audit_outbox (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES public.tenant (id),
    actor        text        NOT NULL,
    action       text        NOT NULL,
    target_ids   jsonb       NOT NULL DEFAULT '[]'::jsonb,
    before_state text,
    after_state  text,
    event_at     timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    relayed_at   timestamptz
);

CREATE INDEX audit_outbox_pending ON public.audit_outbox (tenant_id, id) WHERE relayed_at IS NULL;

ALTER TABLE public.audit_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_outbox FORCE ROW LEVEL SECURITY;

CREATE POLICY audit_outbox_tenant_isolation ON public.audit_outbox
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- the relay may only stamp relayed_at; no row is ever deleted by the application role
GRANT SELECT, INSERT ON public.audit_outbox TO ipaas_app;
GRANT UPDATE (relayed_at) ON public.audit_outbox TO ipaas_app;
REVOKE DELETE, TRUNCATE ON public.audit_outbox FROM ipaas_app;
