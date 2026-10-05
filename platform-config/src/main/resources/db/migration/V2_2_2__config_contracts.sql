-- E3 lane B, T-014: the contract registry tables, the phase-3 cut (ADR-011 Decision 2) - the ONE
-- home for every schema. `contract` carries the SOURCE_REQUEST role only; E5 adds the target roles
-- by widening this CHECK. `contract_version` carries the JSON Schema (draft 2020-12) itself; the
-- flow configuration's interface version references it BY ID and never copies a schema onto the
-- interface. Versions are immutable content: INSERT and SELECT for ipaas_app, no UPDATE grant
-- (T-018's invariant - an approved contract version is immutable, ADR-011 Decision 3 rule 1), the
-- author-never-approver rule in a named table CHECK from the first release (BR-04, data-design).
-- Composition is composite-FK safe: a contract_version can only reference its own tenant's contract.

CREATE TABLE public.contract (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenant (id),
    system_id uuid NOT NULL,
    code      text NOT NULL,
    role      text NOT NULL CHECK (role IN ('SOURCE_REQUEST')),
    name      text NOT NULL,
    CONSTRAINT contract_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT contract_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT contract_system_composite FOREIGN KEY (tenant_id, system_id)
        REFERENCES public.system (tenant_id, id)
);

CREATE TABLE public.contract_version (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 uuid    NOT NULL REFERENCES public.tenant (id),
    contract_id               uuid    NOT NULL,
    version_label             text    NOT NULL CHECK (version_label ~ '^\d+\.\d+$'),
    status                    text    NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'RETIRED')),
    json_schema_draft_2020_12 jsonb   NOT NULL,
    openapi_document          jsonb,
    derived_from_version_id   uuid,
    compatibility             text CHECK (compatibility IN ('COMPATIBLE', 'BREAKING')),
    author_id                 uuid    NOT NULL,
    approver_id               uuid,
    created_at                timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT approver_not_author CHECK (approver_id IS NULL OR approver_id <> author_id),
    CONSTRAINT contract_version_contract_composite FOREIGN KEY (tenant_id, contract_id)
        REFERENCES public.contract (tenant_id, id),
    CONSTRAINT contract_version_derived_from_composite FOREIGN KEY (tenant_id, derived_from_version_id)
        REFERENCES public.contract_version (tenant_id, id),
    CONSTRAINT contract_version_label_unique UNIQUE (tenant_id, contract_id, version_label),
    CONSTRAINT contract_version_tenant_id_key UNIQUE (tenant_id, id)
);

ALTER TABLE public.contract ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.contract_version ENABLE ROW LEVEL SECURITY;

CREATE POLICY contract_tenant_isolation ON public.contract
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE POLICY contract_version_tenant_isolation ON public.contract_version
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- The draft-save API (T-092) stores its sourceSchema field as a draft source contract version:
-- INSERT (a new draft per save) and READ; the schema text itself is never rewritten (T-018).
GRANT SELECT, INSERT ON public.contract TO ipaas_app;
GRANT UPDATE (name) ON public.contract TO ipaas_app;

GRANT SELECT, INSERT ON public.contract_version TO ipaas_app;