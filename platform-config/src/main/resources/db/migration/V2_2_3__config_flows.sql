-- E3 lane B, T-014: the flow tables - `interface` (the flow row), `route` (one destination of an
-- interface) and `mapping` (one per interface-target pair, expressed as mapping.route_id UNIQUE -
-- the schema-level form of ADR-004's one mapping per pair). `interface.ordering` is ADR-011
-- Decision 6's interface setting (PER_KEY default, NONE when a source sequences itself); the
-- interface's `code` is what the ingest URL carries, so no UPDATE grant touches it (immutable once
-- published, in fact never updatable by the application role); onboarding a flow inserts rows only
-- (E3-F1-S1), so every write here is a plain row insert or a narrow-column update. The business-key
-- rule itself is versioned: interface_version carries it mandatory (V2_2_4, architecture.md).
--
-- Composition is composite-FK safe end to end: a route can only point at its own tenant's interface
-- and endpoint, a mapping only at its own tenant's route.

CREATE TABLE public.interface (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES public.tenant (id),
    code              text NOT NULL,
    name              text NOT NULL,
    mode              text NOT NULL DEFAULT 'ASYNC' CHECK (mode IN ('ASYNC', 'SYNC')),
    ordering          text NOT NULL DEFAULT 'PER_KEY' CHECK (ordering IN ('PER_KEY', 'NONE')),
    current_version   integer NOT NULL DEFAULT 0 CHECK (current_version >= 0),
    published_version integer,
    status            text NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT interface_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT interface_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT interface_versions_ordered
        CHECK (published_version IS NULL OR published_version <= current_version)
);

CREATE TABLE public.route (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES public.tenant (id),
    interface_id      uuid NOT NULL,
    target_endpoint_id uuid NOT NULL,
    sequence          integer NOT NULL CHECK (sequence > 0),
    condition         text,
    is_primary        boolean NOT NULL DEFAULT false,
    status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    -- A sync interface has exactly one primary route (data-design invariant 2); ASYNC flows
    -- need no primary, so the index is partial.
    CONSTRAINT route_interface_composite FOREIGN KEY (tenant_id, interface_id)
        REFERENCES public.interface (tenant_id, id),
    CONSTRAINT route_target_composite FOREIGN KEY (tenant_id, target_endpoint_id)
        REFERENCES public.target_endpoint (tenant_id, id),
    CONSTRAINT route_tenant_id_key UNIQUE (tenant_id, id)
);

CREATE INDEX route_single_primary_idx ON public.route (interface_id) WHERE is_primary;

CREATE TABLE public.mapping (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid NOT NULL REFERENCES public.tenant (id),
    route_id           uuid NOT NULL,
    field_mappings     jsonb NOT NULL DEFAULT '[]'::jsonb,
    compiled_expression text NOT NULL,
    test_samples       jsonb,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    -- One mapping per route: mapping belongs to the (interface, target) pair, not the interface.
    CONSTRAINT mapping_route_unique UNIQUE (route_id),
    CONSTRAINT mapping_route_composite FOREIGN KEY (tenant_id, route_id)
        REFERENCES public.route (tenant_id, id)
);

ALTER TABLE public.interface ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.route ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.mapping ENABLE ROW LEVEL SECURITY;

CREATE POLICY interface_tenant_isolation ON public.interface
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE POLICY route_tenant_isolation ON public.route
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE POLICY mapping_tenant_isolation ON public.mapping
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- The configurator edits flow rows freely inside the tenant; the ingest code cannot change.
GRANT SELECT, INSERT ON public.interface TO ipaas_app;
GRANT UPDATE (name, mode, ordering, current_version, published_version, status, updated_at)
    ON public.interface TO ipaas_app;

GRANT SELECT, INSERT ON public.route TO ipaas_app;
GRANT UPDATE (sequence, condition, is_primary, status, updated_at)
    ON public.route TO ipaas_app;

GRANT SELECT, INSERT ON public.mapping TO ipaas_app;
GRANT UPDATE (field_mappings, compiled_expression, test_samples, updated_at)
    ON public.mapping TO ipaas_app;