package io.integrationplatform.delivery.port;

import java.util.UUID;

/**
 * Slot-free re-drive of a bulkhead-deferred delivery (ADR-010 section 2 addendum, drain fix): when a send ends and
 * returns its bulkhead permit, the dispatch asks for ONE deferred delivery of the same target to be moved
 * {@code RETRY_WAIT -> DUE} and handed back to stage 2 at once, instead of waiting for the next
 * {@code DELIVERY_RETRY} run (60 s cadence, at most the per-target cap per run). The retry job remains the safety
 * net for anything this misses.
 *
 * <p>Runs under the caller's bound tenant; opens its own transaction (the state change and its
 * {@code DELIVERY_DUE} outbox row commit together, R11). One call moves at most one row, so the per-target cap holds
 * (one re-drive per freed permit) and there is no loop to bound.
 */
public interface BulkheadRedrive {

    /**
     * Moves the oldest bulkhead-deferred ({@code bulkhead_deferrals > 0}) {@code RETRY_WAIT} delivery of the target to
     * {@code DUE} (ignoring its backoff time: the slot it waited for is free now) and appends its due record.
     *
     * @return {@code true} when a delivery was re-driven; {@code false} when none was waiting (or another
     *     transaction already moved it) - no effect, never an exception for that
     */
    boolean redriveOneDeferred(UUID targetEndpointId);

    /** For callers and slices without a delivery store: nothing is re-driven, the retry job stays the only path. */
    BulkheadRedrive NONE = targetEndpointId -> false;
}
