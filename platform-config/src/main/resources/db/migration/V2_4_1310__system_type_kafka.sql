-- E16 (T-148): the KAFKA system type. The closed set of V2_2_7 (text + CHECK, never ENUM; R10) gains 'KAFKA'
-- so a Kafka-topic target can be registered; no row changes. The stored type stays 'KAFKA' (the key the
-- stage-2 target registry and the settings reader already use); its secrets are the KAFKA_TOPIC target's
-- (TargetCredentialType.ofSystemType). Settings are validated in code on every save (SystemSettings).
ALTER TABLE public.system DROP CONSTRAINT system_type_closed_set;
ALTER TABLE public.system ADD CONSTRAINT system_type_closed_set
    CHECK (type IN ('API', 'DB', 'MQ', 'FILE', 'KAFKA'));

-- Rollback (manual, never run by Flyway; fails while a KAFKA system exists):
--   ALTER TABLE public.system DROP CONSTRAINT system_type_closed_set;
--   ALTER TABLE public.system ADD CONSTRAINT system_type_closed_set CHECK (type IN ('API', 'DB', 'MQ', 'FILE'));
