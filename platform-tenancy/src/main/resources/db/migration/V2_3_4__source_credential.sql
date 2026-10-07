-- E4 lane C, T-098 (ADR-007 Decisions 2 and 3, ADR-009 pattern, architecture.md section 5 control T5).
-- One machine credential of a source system = one confidential identity-provider client. ADR-007's reading: a
-- credential resolves to ONE source system, which belongs to ONE tenant and carries ONE customer identity, so the
-- customer identity lives on this row (public.system is never altered here - E5 extends that table).
--
-- Lifecycle (ADR-007 Decision 3): written PENDING, ACTIVE once the client exists at the identity provider, REVOKED
-- platform-first (revoked_at set in the same statement); idp_disable_pending marks a revoked row whose client could
-- not yet be disabled at the identity provider (the credential reconciliation retries it).
--
-- Fixed contract (lane B wave 1, E5 T-103): table and column names below; more columns may be ADDED later
-- (nullable or defaulted), never renamed or dropped. Closed sets are text + CHECK, never ENUM (R10).

CREATE TABLE public.source_credential (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid        NOT NULL REFERENCES public.tenant (id),
    source_system_id    uuid        NOT NULL,
    customer_id         text        NOT NULL CHECK (btrim(customer_id) <> ''),
    issuer              text        NOT NULL,
    client_id           text        NOT NULL,
    status              text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED')),
    idp_disable_pending boolean     NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now(),
    revoked_at          timestamptz,
    -- One client of one issuer is one credential: the (issuer, azp) pair of a token finds at most one row.
    CONSTRAINT source_credential_issuer_client_key UNIQUE (issuer, client_id),
    CONSTRAINT source_credential_tenant_id_key UNIQUE (tenant_id, id),
    -- A revoked row always says when; no other row does.
    CONSTRAINT source_credential_revoked_at_check CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    -- Only a revoked credential can still wait for its client to be disabled at the identity provider.
    CONSTRAINT source_credential_idp_disable_pending_check CHECK (NOT idp_disable_pending OR status = 'REVOKED')
);

CREATE INDEX source_credential_tenant_id_idx ON public.source_credential (tenant_id);

-- The credential's source system is the tenant's own, by schema: composite key onto public.system (tenant_id, id)
-- (platform-config V2_2_1, which always runs first in the application). ponytail: module test databases that
-- compose platform-tenancy without platform-config (ingest, delivery) have no public.system; there the key is not
-- created and no source_credential row is ever written. Every database the application runs on gets it.
DO $$
BEGIN
    IF to_regclass('public.system') IS NOT NULL THEN
        ALTER TABLE public.source_credential
            ADD CONSTRAINT source_credential_system_composite FOREIGN KEY (tenant_id, source_system_id)
                REFERENCES public.system (tenant_id, id);
    END IF;
END
$$;

-- Row-level security, error-deny form (ADR-009 Decision 5), as V1_3_2: an unset setting fails the statement. No
-- FORCE: the definer bootstrap (V2_3_5, owned by the migration owner) reads before any tenant is bound.
ALTER TABLE public.source_credential ENABLE ROW LEVEL SECURITY;

CREATE POLICY source_credential_tenant_isolation ON public.source_credential
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- Narrow grants: a credential is created (PENDING) and then only changes state; who it belongs to, which client it
-- is and which customer it carries never change. DELETE and TRUNCATE are denied by grant (the row is the history).
GRANT SELECT, INSERT ON public.source_credential TO ipaas_app;
GRANT UPDATE (status, revoked_at, idp_disable_pending) ON public.source_credential TO ipaas_app;
REVOKE DELETE, TRUNCATE ON public.source_credential FROM ipaas_app;
