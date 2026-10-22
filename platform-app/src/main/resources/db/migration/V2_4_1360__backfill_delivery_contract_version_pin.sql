-- Operator replay/retry needs BOTH pins on the delivery (DeliveryOperatorActions: mapping_version_id and
-- contract_version_id). Stage 1 began writing contract_version_id in 1904b6dd; a delivery created before that holds
-- contract_version_id NULL, so its replay and retry answer 409 "not pinned" for ever. The pin is derivable with
-- certainty: a mapping version is immutable and records its target contract (mapping_version.target_contract_version_id,
-- NOT NULL), and the delivery already names its mapping version.
--
-- Copies the pin ONLY where delivery.contract_version_id IS NULL and the delivery's own tenant holds the mapping
-- version it names. A row that already has a pin is never overwritten; a row with no mapping_version_id, one that
-- resolves to no mapping_version row, or one that names another tenant's mapping version is skipped and keeps NULL
-- (and the same 409). A second run finds no NULL pin it can resolve and changes nothing.
--
-- This file lives in platform-app because it reads two modules' tables: delivery (platform-delivery, V2_3_1/V2_3_3_1/
-- V2_3_930) and mapping_version (platform-config, V2_2_730); only the composed app sees both, and its version sorts
-- after both.
--
-- delivery is PARTITIONED BY DAY on created_at. The UPDATE sets contract_version_id only, so no row changes
-- partition (created_at is untouched) and it takes ROW EXCLUSIVE on the parent and the partitions it scans -
-- never the ACCESS EXCLUSIVE lock of partition DDL, and it does not block readers or writers of other rows. The
-- statement has no created_at predicate (a legacy row can sit in any retained day), so it reaches every partition
-- through delivery_mapping_version_idx (tenant_id, mapping_version_id). It runs per tenant and in chunks of 5000
-- rows, so one statement never holds more than a bounded set of row locks and a bounded WAL burst; Flyway runs the
-- script in one transaction, so the row locks are released together at its commit and a concurrent claim or
-- operator action on one of these rows waits for that commit. Updated rows leave the predicate (the pin is no
-- longer NULL), which is what ends the loop; skipped rows never match it, so it cannot spin.
--
-- delivery and mapping_version have FORCE ROW LEVEL SECURITY, which binds the table owner too (unless it is a
-- superuser or holds BYPASSRLS), so the statement runs per tenant with the tenant stamped for this transaction, as
-- V2_4_1330, V2_4_1340 and V2_4_1350 do; the join is on tenant_id AND id.
--
-- No destination check and no audit event: this is a data pin with no destination, and a migration has no actor; the
-- backfill is recorded by this file and the Flyway history.
DO $backfill$
DECLARE
    tenant uuid;
    chunk bigint;
    total bigint;
BEGIN
    FOR tenant IN SELECT id FROM public.tenant LOOP
        PERFORM set_config('app.tenant_id', tenant::text, true);
        total := 0;
        LOOP
            UPDATE public.delivery d SET contract_version_id = mv.target_contract_version_id
                FROM public.mapping_version mv
                WHERE (d.id, d.created_at) IN (
                        SELECT d2.id, d2.created_at
                        FROM public.delivery d2
                        JOIN public.mapping_version mv2
                          ON mv2.tenant_id = d2.tenant_id AND mv2.id = d2.mapping_version_id
                        WHERE d2.tenant_id = tenant
                          AND d2.contract_version_id IS NULL
                          AND mv2.target_contract_version_id IS NOT NULL
                        LIMIT 5000)
                  AND mv.tenant_id = d.tenant_id
                  AND mv.id = d.mapping_version_id
                  AND d.tenant_id = tenant
                  AND d.contract_version_id IS NULL;
            GET DIAGNOSTICS chunk = ROW_COUNT;
            EXIT WHEN chunk = 0;
            total := total + chunk;
        END LOOP;
        RAISE NOTICE 'V2_4_1360: tenant % pinned % delivery row(s)', tenant, total;
    END LOOP;
END
$backfill$;
