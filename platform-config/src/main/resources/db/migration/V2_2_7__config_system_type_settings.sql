-- E5 lane B, T-102 (sanjay-developer, ledger row 278): the systems registry extends the E3 table
-- (ADR-011 Decision 2 - no second systems table). V2_2_1's fixed contract allows ADDED columns,
-- nullable or defaulted: `type` and `settings` are both nullable, so every E3 rows-only registration
-- (T016, the forged-tenant seeding) keeps inserting without them. The type is a closed set as text +
-- CHECK, never ENUM (R10); the settings are the type's JSON object, validated against the type's schema
-- in code on every save (ADR-011 Decision 1) and never holding a secret (unknown keys are refused; R07).
-- A type needs its settings and settings need a type. Row-level security is V2_2_1's error-deny policy
-- on the same table (unchanged). The application role may UPDATE only the two new mutable columns beside
-- V2_2_1's (name, direction); `code` stays out of every UPDATE grant. Flyway runs this file in one
-- transaction.

ALTER TABLE public.system
    ADD COLUMN type text CONSTRAINT system_type_closed_set CHECK (type IN ('API', 'DB', 'MQ', 'FILE')),
    ADD COLUMN settings jsonb CONSTRAINT system_settings_is_object
        CHECK (settings IS NULL OR jsonb_typeof(settings) = 'object'),
    ADD CONSTRAINT system_type_and_settings_together CHECK ((type IS NULL) = (settings IS NULL));

GRANT UPDATE (type, settings) ON public.system TO ipaas_app;

-- Rollback (manual, never run by Flyway):
--   REVOKE UPDATE (type, settings) ON public.system FROM ipaas_app;
--   ALTER TABLE public.system DROP CONSTRAINT system_type_and_settings_together,
--       DROP COLUMN settings, DROP COLUMN type;
