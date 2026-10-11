-- E5 lane A, T-106 (ADR-011 Decision 3 rule 3, E5-C7): every delivery records the target contract version it was
-- created with. V2_3_1 left "contract versions" to an ADD-only migration of a later phase; this is it. The delivery
-- creation itself belongs to the delivery epic (E9) - nothing writes the column yet; the T-106 test reads its shape,
-- and the recorded pin is proven today on the outbox row (V2_1_2).
--
-- Numbered V2_3_3_1 so it runs right after the delivery family exists (V2_3_1 to V2_3_3) on a fresh database; the
-- ALTER on the partitioned parent adds the column to every partition, present and future. Nullable, no default:
-- no existing row is rewritten. No foreign key (the parent is partitioned, and contract_version is the config
-- module's table - the same rule V2_3_1 applies to delivery_id). The table-level INSERT/UPDATE grants of V2_3_1
-- already cover the column.
--
-- Rollback (manual, nothing writes the column yet):
--   ALTER TABLE public.delivery DROP COLUMN contract_version_id;

ALTER TABLE public.delivery ADD COLUMN contract_version_id uuid;

COMMENT ON COLUMN public.delivery.contract_version_id IS
    'The target contract version this delivery was created with (T-106, ADR-011 Decision 3 rule 3); NULL until E9 writes it.';
