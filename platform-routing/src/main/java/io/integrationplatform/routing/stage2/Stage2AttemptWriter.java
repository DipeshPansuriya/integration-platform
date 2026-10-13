package io.integrationplatform.routing.stage2;

import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Instant;
import java.util.UUID;

/**
 * The attempt ledger's writer as stage 2 sees it (T-119; ADR-010 sections 4 and 6: "every call that left the
 * process is exactly one attempt row" - the first try and each redelivery alike, {@code layer = 1}). Lane A1's
 * port: the real adapter binds the call to lane C's append-only delivery ledger (one INSERT per call, grant-no
 * UPDATE/DELETE, R15) - the recording path stays the existing delivery machinery, never a routing-side table.
 * The writer appends and nothing else; it never opens its own transaction beyond the executor's open,
 * tenant-bound one (R08/R11).
 */
public interface Stage2AttemptWriter {

    /**
     * Appends exactly one attempt row for one call that left the process.
     *
     * @param deliveryId the delivery the call belongs to
     * @param layer      1 for a layer-1 (in-attempt) call, 2 for a layer-2 (retried) call
     * @param outcome    the call's classified outcome
     * @param startedAt  when the call was sent
     * @param finishedAt when the call's outcome was decided
     * @param detail     the call's one-line detail (HTTP status line, connection diagnostic), or null
     */
    void record(UUID deliveryId, int layer, OutcomeClass outcome, Instant startedAt, Instant finishedAt,
            String detail);
}