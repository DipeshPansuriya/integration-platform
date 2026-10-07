-- E4 lane B, T-099 (ADR-007 Decision 5, PRD section 3, ADR-009 pattern).
-- Roles are platform rows, never identity-provider roles: a misconfigured mapper can never grant one. One row = one
-- role of one user of one tenant; the server reads them in the request's tenant-bound transaction and enforces the
-- operation -> roles matrix (RolePolicy) on every /api endpoint.
--
-- Fixed contract: table and column names below; more columns may be ADDED later (nullable or defaulted), never renamed
-- or dropped. Closed sets are text + CHECK, never ENUM (R10).
--
-- Rollback (Flyway Community has no undo; run as the migration owner, then delete this version from
-- flyway_schema_history): DROP TABLE public.role_grant; ALTER TABLE public.app_user DROP CONSTRAINT
-- app_user_tenant_id_key;  -- every role is gone: role-gated endpoints answer 403 until grants are re-seeded.

-- The composite reference below needs (tenant_id, id) to be a key of app_user. id alone is already unique, so this
-- only adds the pair as a referenceable key (additive; V1_3_2's columns are untouched).
ALTER TABLE public.app_user ADD CONSTRAINT app_user_tenant_id_key UNIQUE (tenant_id, id);

CREATE TABLE public.role_grant (
    tenant_id  uuid        NOT NULL REFERENCES public.tenant (id),
    user_id    uuid        NOT NULL,
    role       text        NOT NULL
        CHECK (role IN ('TENANT_ADMIN', 'CONFIGURATOR', 'APPROVER', 'SUPPORT', 'READ_ONLY')),
    granted_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT role_grant_tenant_user_role_key UNIQUE (tenant_id, user_id, role),
    -- The grant names a user OF ITS OWN tenant, by schema: a tenant-A row can never point at a tenant-B user.
    CONSTRAINT role_grant_user_composite FOREIGN KEY (tenant_id, user_id) REFERENCES public.app_user (tenant_id, id)
);

-- The read path is (tenant, user): the unique key's leading columns serve it; no extra index.

-- Row-level security, error-deny form (ADR-009 Decision 5), as V1_3_2: an unset setting fails the statement. No FORCE,
-- like every directory table (the owner seeds and migrates; the application role owns nothing).
ALTER TABLE public.role_grant ENABLE ROW LEVEL SECURITY;

CREATE POLICY role_grant_tenant_isolation ON public.role_grant
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- Narrow grants: the application only READS roles. Granting and revoking a role is the tenant-admin user screen of a
-- later epic (operation MANAGE_USERS_AND_ROLES); it widens this grant in its own migration. Writes are denied by grant.
GRANT SELECT ON public.role_grant TO ipaas_app;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON public.role_grant FROM ipaas_app;
