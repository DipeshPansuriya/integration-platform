-- E16-T0: per-endpoint Kafka topic settings. No Kafka settings precedent existed in
-- migrations, so this mirrors the MQ settings shape: V2_2_7's JSON-object settings
-- with a named jsonb_typeof CHECK, V2_3_7's tenant-scoped credential constraints and
-- V2_2_1's error-deny row-level security (ADR-009 Decision 5). Settings are validated
-- against the KAFKA_TOPIC type's schema in code on every save (ADR-011 Decision 1)
-- and never hold a secret (unknown keys are refused; R07) — credentials for
-- KAFKA_TOPIC endpoints remain in target_credential (V2_3_7). Flyway runs this file
-- in one transaction.
CREATE TABLE public.kafka_topic_setting (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES public.tenant (id),
    target_endpoint_id uuid NOT NULL,
    settings jsonb NOT NULL CONSTRAINT kafka_topic_setting_settings_is_object
        CHECK (jsonb_typeof(settings) = 'object'),
    CONSTRAINT kafka_topic_setting_one_per_endpoint UNIQUE (tenant_id, target_endpoint_id),
    CONSTRAINT kafka_topic_setting_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT kafka_topic_setting_endpoint_composite FOREIGN KEY (tenant_id, target_endpoint_id)
        REFERENCES public.target_endpoint (tenant_id, id)
);

ALTER TABLE public.kafka_topic_setting ENABLE ROW LEVEL SECURITY;
CREATE POLICY kafka_topic_setting_tenant_isolation ON public.kafka_topic_setting
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

GRANT SELECT, INSERT, DELETE ON public.kafka_topic_setting TO ipaas_app;
GRANT UPDATE (settings) ON public.kafka_topic_setting TO ipaas_app;

-- Rollback (manual, never run by Flyway):
--   REVOKE UPDATE (settings) ON public.kafka_topic_setting FROM ipaas_app;
--   REVOKE SELECT, INSERT, DELETE ON public.kafka_topic_setting FROM ipaas_app;
--   DROP POLICY kafka_topic_setting_tenant_isolation ON public.kafka_topic_setting;
--   ALTER TABLE public.kafka_topic_setting DISABLE ROW LEVEL SECURITY;
--   DROP TABLE public.kafka_topic_setting;