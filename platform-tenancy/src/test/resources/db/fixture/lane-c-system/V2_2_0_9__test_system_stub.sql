-- TEST FIXTURE ONLY (E4 lane C, T-098). platform-tenancy's own test database has no platform-config migrations, so
-- public.system (owned by platform-config V2_2_1, never altered here) is absent. This stub mirrors exactly the part
-- of it that source_credential references - id, tenant_id and the composite key (tenant_id, id) - so the tenancy
-- container tests run V2_3_4 the way platform-app does (system present, composite foreign key created). It is never
-- on a production classpath.

CREATE TABLE public.system (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenant (id),
    code      text NOT NULL,
    CONSTRAINT system_tenant_id_key UNIQUE (tenant_id, id)
);
