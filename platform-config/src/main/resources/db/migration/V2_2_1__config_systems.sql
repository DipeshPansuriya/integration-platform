-- E3 lane B, T-014 (arjun-fullstack's brief, ledger row 278): the systems tables - `system` and
-- `target_endpoint` (ADR-011 Decision 2 phase-3 cut; E5 extends them with `type` and `settings`, so
-- those are deliberately absent). Style follows V2_0_1__outbox.sql: closed sets are text + CHECK,
-- never ENUM (R10); row-level security in the error-deny form (ADR-009 Decision 5); grants to
-- ipaas_app are exactly narrow - UPDATE only on mutable columns. `code` is never in an UPDATE grant
-- (it keys the URL and the UI); `credential_ref` holds only an OpenBao PATH, never a value (R07);
-- `target_endpoint` composes its system through a composite foreign key (tenant_id, system_id), so
-- a cross-tenant parent is impossible by schema, not by a trigger.
--
-- Fixed contract: table and column names below; more columns may be ADDED later by a lane's own
-- migration (nullable or defaulted), never renamed or dropped.

CREATE TABLE public.system (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenant (id),
    code      text NOT NULL,
    name      text NOT NULL,
    direction text NOT NULL CHECK (direction IN ('SOURCE', 'TARGET', 'BOTH')),
    CONSTRAINT system_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT system_tenant_id_key UNIQUE (tenant_id, id)
);

CREATE TABLE public.target_endpoint (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 uuid        NOT NULL REFERENCES public.tenant (id),
    system_id                 uuid        NOT NULL,
    code                      text        NOT NULL,
    name                      text        NOT NULL,
    base_url                  text        NOT NULL,
    http_method               text        NOT NULL
        CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    auth_method               text        NOT NULL
        CHECK (auth_method IN ('NO_AUTH', 'API_KEY', 'BASIC', 'DIGEST',
                               'OAUTH2_CLIENT_CREDENTIALS', 'MUTUAL_TLS')),
    credential_ref            text,
    timeout_ms                integer     NOT NULL CHECK (timeout_ms BETWEEN 100 AND 300000),
    circuit_failure_threshold integer     NOT NULL DEFAULT 5 CHECK (circuit_failure_threshold > 0),
    circuit_open_seconds      integer     NOT NULL DEFAULT 30 CHECK (circuit_open_seconds > 0),
    error_dictionary          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    idempotent_receive        boolean     NOT NULL DEFAULT false,
    CONSTRAINT target_endpoint_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT target_endpoint_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT target_endpoint_system_composite FOREIGN KEY (tenant_id, system_id)
        REFERENCES public.system (tenant_id, id)
);

-- Row-level security, error-deny form (ADR-009 Decision 5), as V1_3_2: an unset setting fails
-- the statement, so an unstamped session sees nothing instead of everything.
ALTER TABLE public.system ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.target_endpoint ENABLE ROW LEVEL SECURITY;

CREATE POLICY system_tenant_isolation ON public.system
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE POLICY target_endpoint_tenant_isolation ON public.target_endpoint
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- Registration and tuning are configuration writes; DELETE waits for a retire flow (later epic).
GRANT SELECT, INSERT ON public.system TO ipaas_app;
GRANT UPDATE (name, direction) ON public.system TO ipaas_app;

GRANT SELECT, INSERT ON public.target_endpoint TO ipaas_app;
GRANT UPDATE (code, name, base_url, http_method, auth_method, credential_ref, timeout_ms,
              circuit_failure_threshold, circuit_open_seconds, error_dictionary,
              idempotent_receive) ON public.target_endpoint TO ipaas_app;