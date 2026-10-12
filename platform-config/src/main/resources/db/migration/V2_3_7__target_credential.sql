-- E5 lane C, T-103 + T-185 (rohit-developer, ledger row 278; ADR-007 Decision 7 rules 1-3, 8-10). The target
-- credentials: every credential the platform stores for a target is its own row, one per field (rule 10), sealed in the
-- JVM with envelope encryption - the value under its own random 256-bit data key (AES-256-GCM, associated data
-- tc:v1|tenant|target endpoint|field), the data key wrapped with the key-encryption key named by kek_id (AES-256-GCM,
-- associated data dek:v1|tenant|credential id|kek id). The key-encryption key itself is never here: it comes only
-- from the deployment's secret store (PLATFORM_CREDENTIAL_KEK_<ID>), so this table holds ciphertext and key ids only.
--
-- Layout of both blobs: 12-byte random nonce, then the AES-GCM ciphertext with its 16-byte tag (a wrapped 32-byte data
-- key is therefore exactly 60 bytes). A save of an existing field keeps the row id (snapshots, audit events and exports
-- name a credential by id only - rule 9) and replaces its blobs (a fresh data key and nonces - rule 2).
--
-- Closed sets are text + CHECK, never ENUM (R10). Row-level security in the error-deny form (ADR-009 Decision 5); the
-- endpoint is the tenant's own by schema (composite key onto target_endpoint). Grants are narrow: the application
-- inserts and re-seals; which tenant, endpoint, type and field a row is never changes; DELETE and TRUNCATE are denied
-- (removing a secret is a later feature). Flyway runs this file in one transaction.

CREATE TABLE public.target_credential (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES public.tenant (id),
    target_endpoint_id uuid        NOT NULL,
    target_type        text        NOT NULL
        CONSTRAINT target_credential_type_closed_set CHECK (target_type IN ('API', 'MQ_CSV', 'DB', 'FILE', 'KAFKA_TOPIC')),
    field              text        NOT NULL,
    kek_id             text        NOT NULL
        CONSTRAINT target_credential_kek_id_format CHECK (kek_id ~ '^[A-Z0-9]{1,32}$' AND kek_id <> 'ACTIVE'),
    wrapped_key        bytea       NOT NULL
        CONSTRAINT target_credential_wrapped_key_length CHECK (octet_length(wrapped_key) = 60),
    ciphertext         bytea       NOT NULL
        CONSTRAINT target_credential_ciphertext_length CHECK (octet_length(ciphertext) >= 29),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    -- ADR-007 Decision 7 rule 10: the fields of each target type, exactly (TargetCredentialType in code).
    CONSTRAINT target_credential_field_of_type CHECK ((target_type, field) IN (
        ('API', 'API_KEY'),
        ('API', 'BEARER_TOKEN'),
        ('API', 'BASIC_PASSWORD'),
        ('API', 'OAUTH_CLIENT_SECRET'),
        ('API', 'CLIENT_CERT_PRIVATE_KEY'),
        ('API', 'CLIENT_CERT_KEYSTORE_PASSWORD'),
        ('MQ_CSV', 'QUEUE_MANAGER_PASSWORD'),
        ('MQ_CSV', 'TLS_KEYSTORE_PASSWORD'),
        ('MQ_CSV', 'TLS_TRUSTSTORE_PASSWORD'),
        ('DB', 'DB_ACCOUNT_PASSWORD'),
        ('FILE', 'SHARE_ACCOUNT_PASSWORD'),
        ('KAFKA_TOPIC', 'SASL_PASSWORD'),
        ('KAFKA_TOPIC', 'TLS_KEYSTORE_PASSWORD'))),
    CONSTRAINT target_credential_one_per_field UNIQUE (tenant_id, target_endpoint_id, field),
    CONSTRAINT target_credential_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT target_credential_endpoint_composite FOREIGN KEY (tenant_id, target_endpoint_id)
        REFERENCES public.target_endpoint (tenant_id, id)
);

-- Rule 7's gate (a key-encryption key is retired when 0 rows reference it) and the start-up key check read by kek_id.
CREATE INDEX target_credential_kek_id_idx ON public.target_credential (kek_id);

ALTER TABLE public.target_credential ENABLE ROW LEVEL SECURITY;

CREATE POLICY target_credential_tenant_isolation ON public.target_credential
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

GRANT SELECT, INSERT ON public.target_credential TO ipaas_app;
GRANT UPDATE (kek_id, wrapped_key, ciphertext, updated_at) ON public.target_credential TO ipaas_app;
REVOKE DELETE, TRUNCATE ON public.target_credential FROM ipaas_app;

-- ADR-007 Decision 7 rule 5's start-up half ("every key id referenced by a credential row is present") needs the key ids
-- of EVERY tenant: a cross-tenant read, i.e. a new definer function under architecture.md section 5 control T5 (today
-- exactly four) and a reviewed @TenantBoundaryCrossing. That is an architecture decision, not made here (T44 hand-back,
-- Needs decision); until then the readiness check relies on the send-time reports.

-- Rollback (manual, never run by Flyway):
--   DROP TABLE public.target_credential;
