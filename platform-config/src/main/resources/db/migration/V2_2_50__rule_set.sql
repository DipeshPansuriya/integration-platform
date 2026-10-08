-- E6 lane B, T-023: rule sets - the decision tables a configurator authors on a flow (ADR-003: GoRules ZEN,
-- JSON Decision Model in jsonb). One row per rule set of one interface; `kind` is the closed set of what the
-- table decides (R10: text + CHECK, never ENUM).
--
-- `content` is the JDM as jsonb (validated JSON, queryable, the ADR-003 storage); `content_text` is the same JSON
-- exactly as authored, because jsonb re-orders keys and the editor must re-open a table exactly as it was left.
-- The CHECK makes the two one fact: they can never disagree.
--
-- Composite FKs keep a rule set inside its own tenant's interface. RLS is enabled AND forced (no definer
-- function reads this table, so the owner is subject to the policy too); the application role inserts and
-- edits the name and content only - kind and interface are fixed at creation, nothing is ever deleted.

CREATE TABLE public.rule_set (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES public.tenant (id),
    interface_id  uuid        NOT NULL,
    kind          text        NOT NULL CHECK (kind IN ('VALIDATION', 'ROUTING', 'LOOKUP')),
    name          text        NOT NULL CHECK (btrim(name) <> ''),
    content       jsonb       NOT NULL CHECK (jsonb_typeof(content) = 'object'),
    content_text  text        NOT NULL,
    author_id     uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT rule_set_content_as_authored CHECK (content_text::jsonb = content),
    CONSTRAINT rule_set_interface_composite FOREIGN KEY (tenant_id, interface_id)
        REFERENCES public.interface (tenant_id, id),
    CONSTRAINT rule_set_tenant_id_key UNIQUE (tenant_id, id),
    -- the target of lookup_table's (tenant, rule set, kind) FK: a lookup table can only name a LOOKUP rule set
    CONSTRAINT rule_set_tenant_id_kind_key UNIQUE (tenant_id, id, kind)
);

CREATE INDEX rule_set_interface_idx ON public.rule_set (tenant_id, interface_id);

ALTER TABLE public.rule_set ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.rule_set FORCE ROW LEVEL SECURITY;

CREATE POLICY rule_set_tenant_isolation ON public.rule_set
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

GRANT SELECT, INSERT ON public.rule_set TO ipaas_app;
GRANT UPDATE (name, content, content_text, author_id, updated_at) ON public.rule_set TO ipaas_app;

-- Rollback (manual, owner only; Flyway community has no undo):
--   DROP TABLE public.rule_set;   -- after V2_2_51's lookup_table is dropped
