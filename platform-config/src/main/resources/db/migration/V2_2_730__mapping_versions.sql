-- E7 lane C1, T-190 (ADR-013 mapping versions, Accepted 2026-10-12): the mapping's binding columns, the
-- immutable content-addressed mapping_version table, and the sample-run record table of the gate (lane C2
-- implements the MappingSampleRuns seam over it):
--
-- 1. Bindings. Every mapping binds exactly one source and one target contract version (ADR-004 rule 3,
--    ADR-011 Decision 3 binding edge [Verified ADR-004:56-58]). The columns are nullable: the '$' default
--    mapping a flow create drafts for a new route is unbound, and T-190's publish-time pin check refuses
--    publishing any route whose mapping is still unbound. The composite foreign keys make a cross-tenant
--    binding impossible at the database (the E7MappingMatrix leg); ADR-013's DDL is the model.
--    binding_tenant_id_key (UNIQUE (tenant_id, id)) makes the table a composite-FK parent.
-- 2. mapping_version. Created by the application INSIDE the save that changes a mapping's content (the
--    mapping save API owns content edits; a flow draft-save only snapshots whatever the live rows hold and
--    therefore mints no rows - a save that touches only a route condition creates 0 versions): deduplicated
--    by content_sha256 over the canonical form of (engine, source binding, target binding, field_mappings,
--    compiled_expression) with INSERT ... ON CONFLICT DO NOTHING. An unchanged mapping keeps its id; a
--    mapping returning to earlier content reuses that earlier row. No UPDATE, no DELETE (SELECT, INSERT
--    only): deliveries (E9) pin it and replay reads it. version is per mapping, max + 1 at insert; two
--    concurrent saves of DIFFERENT content can contend on mapping_version_number_unique and the API seam
--    answers that 409 (the DeclaredResponses contract) - the unique key is the serialisation point.
-- 3. mapping_sample_run (ADR-013 Decision 6, lane C2's gate): INSERT/SELECT only, keyed by content hash and
--    sample hash, so testing unsaved content creates no version row.

ALTER TABLE public.mapping
    ADD COLUMN source_contract_version_id uuid,
    ADD COLUMN target_contract_version_id uuid,
    ADD CONSTRAINT mapping_tenant_id_key UNIQUE (tenant_id, id),
    ADD CONSTRAINT mapping_source_contract_composite FOREIGN KEY (tenant_id, source_contract_version_id)
        REFERENCES public.contract_version (tenant_id, id),
    ADD CONSTRAINT mapping_target_contract_composite FOREIGN KEY (tenant_id, target_contract_version_id)
        REFERENCES public.contract_version (tenant_id, id);

-- The binding columns are the configurator's to set (through the mapping save API); V2_2_3 granted the
-- content columns, new columns need their own grant (R10: grants are per column list).
GRANT UPDATE (source_contract_version_id, target_contract_version_id) ON public.mapping TO ipaas_app;

CREATE TABLE public.mapping_version (
    id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                  uuid    NOT NULL REFERENCES public.tenant (id),
    mapping_id                 uuid    NOT NULL,
    version                    integer NOT NULL CHECK (version > 0),
    source_contract_version_id uuid    NOT NULL,
    target_contract_version_id uuid    NOT NULL,
    -- The closed set ADR-006 picks; Release 1 ships exactly the JSONata engine (ADR-006 Accepted).
    engine                     text    NOT NULL CHECK (engine IN ('JSONATA')),
    field_mappings             jsonb   NOT NULL,
    compiled_expression        text    NOT NULL,
    content_sha256             bytea   NOT NULL CHECK (length(content_sha256) = 32),
    author_id                  uuid    NOT NULL,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mapping_version_mapping_composite FOREIGN KEY (tenant_id, mapping_id)
        REFERENCES public.mapping (tenant_id, id),
    CONSTRAINT mapping_version_source_composite FOREIGN KEY (tenant_id, source_contract_version_id)
        REFERENCES public.contract_version (tenant_id, id),
    CONSTRAINT mapping_version_target_composite FOREIGN KEY (tenant_id, target_contract_version_id)
        REFERENCES public.contract_version (tenant_id, id),
    CONSTRAINT mapping_version_content_unique UNIQUE (tenant_id, mapping_id, content_sha256),
    CONSTRAINT mapping_version_number_unique UNIQUE (mapping_id, version),
    CONSTRAINT mapping_version_tenant_id_key UNIQUE (tenant_id, id)
);

ALTER TABLE public.mapping_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.mapping_version FORCE ROW LEVEL SECURITY;

CREATE POLICY mapping_version_tenant_isolation ON public.mapping_version
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- Immutable content: the application role reads and inserts, never changes or removes (ADR-013 Decision 1).
GRANT SELECT, INSERT ON public.mapping_version TO ipaas_app;

CREATE TABLE public.mapping_sample_run (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid    NOT NULL REFERENCES public.tenant (id),
    mapping_id     uuid    NOT NULL,
    content_sha256 bytea   NOT NULL CHECK (length(content_sha256) = 32),
    sample_sha256  bytea   NOT NULL CHECK (length(sample_sha256) = 32),
    passed         boolean NOT NULL,
    actor_id       uuid    NOT NULL,
    ran_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mapping_sample_run_mapping_composite FOREIGN KEY (tenant_id, mapping_id)
        REFERENCES public.mapping (tenant_id, id),
    CONSTRAINT mapping_sample_run_tenant_id_key UNIQUE (tenant_id, id)
);

ALTER TABLE public.mapping_sample_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.mapping_sample_run FORCE ROW LEVEL SECURITY;

CREATE POLICY mapping_sample_run_tenant_isolation ON public.mapping_sample_run
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

GRANT SELECT, INSERT ON public.mapping_sample_run TO ipaas_app;