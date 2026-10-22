-- T172 (BS-10): a delivery that pins a mapping version MUST pin its target contract version, or the operator replay and
-- retry answer 409 "not pinned" for ever (DeliveryOperatorActions). Stage 1 now refuses to plan such a delivery
-- (Stage1FanOut.plan) and the stage-1 insert refuses it too (JdbcDeliveryRepository); this CHECK is the last line:
-- the database itself rejects the pair "mapping pinned, contract not pinned".
--
-- A route WITHOUT a mapping (mapping_version_id IS NULL) is allowed to have no contract pin: its replay pins the
-- target endpoint's active contract at replay time (Dipesh decision 2026-10-22).
--
-- PG 17 on the PARTITIONED delivery table (PARTITION BY RANGE (created_at), daily):
--   * ADD CONSTRAINT ... CHECK ... NOT VALID is supported on a partitioned parent (PG 17; only FOREIGN KEY ... NOT VALID
--     on a partitioned table waited for PG 18). It is recorded on the parent and on every existing partition, takes
--     ACCESS EXCLUSIVE only for the catalog change (no scan), and every partition created later inherits it.
--   * VALIDATE CONSTRAINT on the parent validates each partition in turn under SHARE UPDATE EXCLUSIVE, so readers and
--     writers carry on.
-- Chosen: add NOT VALID, then validate only when no row violates it (V2_4_1360 backfilled every row that can be
-- derived, so a violator is a row whose mapping version is missing or another tenant's). When a violator exists the
-- constraint stays NOT VALID and a WARNING names the count: it still guards every NEW row, and the violators are
-- listed by
--   SELECT id, created_at FROM public.delivery WHERE mapping_version_id IS NOT NULL AND contract_version_id IS NULL;
-- A NOT VALID CHECK is still evaluated when a violating row is UPDATED, so repair such a row (pin it, or clear its
-- mapping pin) before its next state change; then run
--   ALTER TABLE public.delivery VALIDATE CONSTRAINT delivery_contract_pin_check;
--
-- Rollback (manual): ALTER TABLE public.delivery DROP CONSTRAINT delivery_contract_pin_check;

ALTER TABLE public.delivery
    ADD CONSTRAINT delivery_contract_pin_check
    CHECK (mapping_version_id IS NULL OR contract_version_id IS NOT NULL) NOT VALID;

DO $validate$
DECLARE
    violators bigint;
BEGIN
    SELECT count(*) INTO violators FROM public.delivery
        WHERE mapping_version_id IS NOT NULL AND contract_version_id IS NULL;
    IF violators = 0 THEN
        ALTER TABLE public.delivery VALIDATE CONSTRAINT delivery_contract_pin_check;
    ELSE
        RAISE WARNING 'V2_4_1370: % delivery row(s) pin a mapping version without a contract version; delivery_contract_pin_check stays NOT VALID (new rows are guarded)', violators;
    END IF;
END
$validate$;
