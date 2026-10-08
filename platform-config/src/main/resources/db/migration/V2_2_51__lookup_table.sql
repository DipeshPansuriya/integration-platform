-- E6 lane B, T-023: lookup tables - the tenant-scoped, named lookup a LOOKUP rule set is registered as, so a
-- mapping can name it ("country codes", E7) and its entries (lookup_entry, E7 T-163: immediate and audited edits,
-- OQ-07 stays Dipesh's) have one row to hang off. The decision table itself (its JDM jsonb) lives once, in its
-- rule_set row: one home for the content, so the authored table and the lookup can never diverge.
--
-- The (tenant_id, rule_set_id, kind) FK with kind fixed to 'LOOKUP' makes "a lookup table is a LOOKUP rule set
-- of the same tenant" a schema fact. RLS enabled and forced, like rule_set.

CREATE TABLE public.lookup_table (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES public.tenant (id),
    rule_set_id  uuid        NOT NULL,
    kind         text        NOT NULL DEFAULT 'LOOKUP' CHECK (kind = 'LOOKUP'),
    name         text        NOT NULL CHECK (btrim(name) <> ''),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT lookup_table_rule_set_composite FOREIGN KEY (tenant_id, rule_set_id, kind)
        REFERENCES public.rule_set (tenant_id, id, kind),
    CONSTRAINT lookup_table_rule_set_unique UNIQUE (rule_set_id),
    CONSTRAINT lookup_table_name_unique UNIQUE (tenant_id, name),
    CONSTRAINT lookup_table_tenant_id_key UNIQUE (tenant_id, id)
);

ALTER TABLE public.lookup_table ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.lookup_table FORCE ROW LEVEL SECURITY;

CREATE POLICY lookup_table_tenant_isolation ON public.lookup_table
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

GRANT SELECT, INSERT ON public.lookup_table TO ipaas_app;
GRANT UPDATE (name, updated_at) ON public.lookup_table TO ipaas_app;

-- Rollback (manual, owner only; Flyway community has no undo):
--   DROP TABLE public.lookup_table;
