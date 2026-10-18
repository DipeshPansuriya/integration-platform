-- T-066 / E12-F1-S2 (row 278 T108): the flow's masked-field list - the payload paths whose values must never appear
-- in a log line or a tracking API answer. Stored on the interface row (the working copy the flow API edits) and
-- captured into every interface_version snapshot (interface.maskedFields), so the published, immutable version is
-- what intake and the tracking read mask by.
--
-- An array of path strings, empty by default: every existing flow keeps its behaviour and its snapshot bytes.
--
-- Rollback (no undo-file convention in this repo):
--   REVOKE UPDATE (masked_fields) ON public.interface FROM ipaas_app;
--   ALTER TABLE public.interface DROP COLUMN masked_fields;

ALTER TABLE public.interface
    ADD COLUMN masked_fields jsonb NOT NULL DEFAULT '[]'::jsonb
        CONSTRAINT interface_masked_fields_is_array CHECK (jsonb_typeof(masked_fields) = 'array');

-- V2_2_3 grants UPDATE column by column; the app role may edit the new column too (INSERT is table-wide already).
GRANT UPDATE (masked_fields) ON public.interface TO ipaas_app;
