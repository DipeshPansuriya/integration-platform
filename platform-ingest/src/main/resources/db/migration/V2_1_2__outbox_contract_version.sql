-- E5 lane A, T-106 (ADR-011 Decision 3 rule 3, E5-C7): every message records the contract version it used. The
-- intake (E11) and the delivery creation (E9) do not exist yet, so the deepest existing seam records it: the outbox
-- row a caller appends inside its own transaction carries the contract version that caller resolved.
--
-- Additive only, as V2_0_1's fixed contract allows: one nullable column, no default, no rewrite of existing rows.
-- No foreign key: contract_version belongs to the config module and the outbox to ingest; the id is a pin, read back
-- through the config module (the same "ids only" rule as the Kafka record, ADR-011 Decision 7). The table-level
-- INSERT grant of V2_0_1 already covers the new column; no UPDATE grant - a recorded pin is never rewritten.
--
-- Rollback (manual, nothing reads the column yet outside the T-106 tests):
--   ALTER TABLE public.outbox DROP COLUMN contract_version_id;

ALTER TABLE public.outbox ADD COLUMN contract_version_id uuid;

COMMENT ON COLUMN public.outbox.contract_version_id IS
    'The contract version the appending caller resolved for this record (T-106); NULL for records written without one.';
