-- E11 T-196 (T115): per-business-key ordering sequence. The intake transaction upserts
-- (tenant, interface, business_key) first, distinct keys in key-hash order, and stores the returned
-- last_seq as message.key_seq. Skipped when interface.ordering = 'NONE'.
-- Rollback: DROP TABLE public.ordering_key;
CREATE TABLE public.ordering_key (
    id           bigint NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    uuid   NOT NULL REFERENCES public.tenant (id),
    interface_id uuid   NOT NULL,
    business_key text   NOT NULL,
    last_seq     bigint NOT NULL CHECK (last_seq >= 1),
    CONSTRAINT ordering_key_per_interface UNIQUE (tenant_id, interface_id, business_key)
);

ALTER TABLE public.ordering_key ENABLE ROW LEVEL SECURITY;

CREATE POLICY ordering_key_tenant_isolation ON public.ordering_key
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

GRANT SELECT, INSERT, UPDATE ON public.ordering_key TO ipaas_app;
