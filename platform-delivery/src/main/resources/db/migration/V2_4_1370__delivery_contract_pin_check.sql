-- T172 (BS-10): a delivery that pins a mapping version MUST pin its target contract version, or the operator replay
-- and retry answer 409 "not pinned" for ever (DeliveryOperatorActions). Stage 1 now refuses to plan such a delivery
-- (Stage1FanOut.plan) and the stage-1 insert refuses it too (JdbcDeliveryRepository); this CHECK is the last line:
-- the database itself rejects the pair "mapping pinned, contract not pinned".
--
-- A route WITHOUT a mapping (mapping_version_id IS NULL) is allowed to have no contract pin: its replay pins the
-- target endpoint's active contract at replay time (Dipesh decision 2026-10-22).
--
-- T190 (review A, blocker): this file ONLY adds the constraint NOT VALID; the validation is V2_4_1400, a migration of
-- its own. Flyway runs each migration file in ONE transaction, and ADD CONSTRAINT takes ACCESS EXCLUSIVE on the
-- parent and on every partition until that transaction commits. With the validation scan in the same file the lock
-- stayed held through every full-table scan and delivery was blocked for the duration (the earlier comment that
-- readers and writers carry on was wrong). Here the lock is held only for the catalog change (no scan).
--   * PG 17, PARTITIONED delivery table (PARTITION BY RANGE (created_at), daily): ADD CONSTRAINT ... CHECK ... NOT
--     VALID is supported on a partitioned parent (only FOREIGN KEY ... NOT VALID waited for PG 18). It is recorded on
--     the parent and on every existing partition, and every partition created later inherits it.
--   * A NOT VALID CHECK guards every NEW row at once. It is also evaluated when an existing violating row is
--     UPDATED, so a leftover violator would fail its next state change: V2_4_1400 therefore refuses to finish (the
--     deploy stops with a clear message) instead of leaving violators behind. Violators are listed by
--       SELECT id, created_at FROM public.delivery WHERE mapping_version_id IS NOT NULL AND contract_version_id IS NULL;
--
-- Applied-migration note: this file changed (checksum) in T190 before it was ever pushed or deployed (it sits on local
-- main since T172 only). A developer database that already applied the earlier text needs `flyway repair` (or a
-- fresh database); QA and production have never run it.
--
-- Rollback (manual): ALTER TABLE public.delivery DROP CONSTRAINT delivery_contract_pin_check;

ALTER TABLE public.delivery
    ADD CONSTRAINT delivery_contract_pin_check
    CHECK (mapping_version_id IS NULL OR contract_version_id IS NOT NULL) NOT VALID;
