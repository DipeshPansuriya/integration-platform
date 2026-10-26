-- T190 (review A, blocker): validates delivery_contract_pin_check (added NOT VALID by V2_4_1370) in a migration of its
-- own, so the scan runs in a SEPARATE Flyway transaction. VALIDATE CONSTRAINT takes only SHARE UPDATE EXCLUSIVE (on
-- the partitioned parent and, in turn, on each partition): readers and writers carry on, only DDL waits. The earlier
-- single-file form held the ACCESS EXCLUSIVE lock of ADD CONSTRAINT through a count(*) pre-scan AND the validation
-- scan; that pre-scan is gone - the validation is the scan.
--
-- A violating row (mapping version pinned, contract version missing, i.e. a row V2_4_1360 could not derive) makes the
-- validation fail. The migration then ABORTS with a message naming the repair, rather than leaving a NOT VALID
-- constraint that fails the violator's next UPDATE and strands an in-flight delivery in SENDING. The deploy stops
-- before the application starts, so nothing is in flight; the transaction rolls back and no history row is written, so
-- after the repair the same migration runs again. Repair (pin the contract, or clear the mapping pin):
--   SELECT id, created_at FROM public.delivery WHERE mapping_version_id IS NOT NULL AND contract_version_id IS NULL;
--
-- PRE-RELEASE CHECK (memory m-ceeeea): the backfill V2_4_1360 (platform-app) runs in ONE transaction, at about 2-3
-- minutes per 1M NULL-pin rows. Before releasing to an environment with a large delivery table count the rows it
-- will touch (contract_version_id IS NULL AND mapping_version_id IS NOT NULL); above ~1M rows run the backfill in
-- per-day commits by hand first (UPDATE ... WHERE created_at >= day AND created_at < day + 1, one commit per day) so
-- this validation finds nothing, and so the single backfill transaction does not hold its row locks and WAL burst
-- for the whole table.

DO $validate$
BEGIN
    ALTER TABLE public.delivery VALIDATE CONSTRAINT delivery_contract_pin_check;
EXCEPTION
    WHEN check_violation THEN
        RAISE EXCEPTION 'V2_4_1400: delivery rows pin a mapping version without a contract version, so delivery_contract_pin_check cannot be validated. Repair them (pin the contract version or clear the mapping pin), then rerun the migration. List them with: SELECT id, created_at FROM public.delivery WHERE mapping_version_id IS NOT NULL AND contract_version_id IS NULL'
            USING ERRCODE = 'check_violation';
END
$validate$;
