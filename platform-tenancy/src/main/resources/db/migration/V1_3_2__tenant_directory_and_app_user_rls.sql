-- E1 lane C, T-005 + T-157 (ADR-009 Decisions 1, 2 and 5; ADR-007 Decision 2; architecture.md section 3).
-- The tenant directory and the human users, both under row-level security keyed on the transaction-local
-- setting app.tenant_id (stamped per transaction, ADR-007 Decision 6).
--
-- Fixed contract other lanes build against: table and column names below; more columns may be ADDED later
-- (nullable or defaulted), never renamed. Closed sets are text + CHECK, never ENUM (R10).

CREATE TABLE public.tenant (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code      text NOT NULL UNIQUE,
    status    text NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    time_zone text NOT NULL DEFAULT 'Etc/UTC'
);

CREATE TABLE public.app_user (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenant (id),
    issuer    text NOT NULL,
    subject   text NOT NULL,
    status    text NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT app_user_issuer_subject_key UNIQUE (issuer, subject)
);

CREATE INDEX app_user_tenant_id_idx ON public.app_user (tenant_id);

-- Row-level security, error-deny form (ADR-009 Decision 5): an unset or empty setting makes the uuid cast
-- fail the statement, so a query outside a stamped transaction returns no rows at all.
-- No FORCE ROW LEVEL SECURITY, deliberately: FORCE would subject the table owner too, and the definer
-- bootstrap (V1_3_3, owned by the migration owner) runs before any tenant is bound - it would then fail on
-- the empty setting. The application role is policed because it owns no table (proved by the grant test).
ALTER TABLE public.tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_user ENABLE ROW LEVEL SECURITY;

-- ADR-009 Decision 1: the directory shows the own row only. USING only - there is no write grant to check.
CREATE POLICY tenant_dir_self ON public.tenant
    USING (id = current_setting('app.tenant_id')::uuid);

CREATE POLICY app_user_tenant_isolation ON public.app_user
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

-- E1 grants: read only. ADR-009 Decision 2: writes to the directory are denied by grant, not by the
-- absence of a policy, so a later "GRANT ALL" convenience cannot reopen them.
GRANT SELECT ON public.tenant, public.app_user TO ipaas_app;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON public.tenant FROM ipaas_app;
