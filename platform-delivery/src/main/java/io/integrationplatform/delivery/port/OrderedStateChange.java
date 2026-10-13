package io.integrationplatform.delivery.port;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The ONE lock order for every state-changing transaction (E9 seam, arjun-fullstack, F14; T-197, ADR-010 section 7,
 * ADR-011 Decision 6): the ordering-key advisory lock first - skipped only when ordering is {@code NONE} (a
 * {@code null}/{@code blank} key) - then the message row, then its deliveries in id order. Stage 1 (T-178), the
 * claim, the bulkhead-full deferral (T-198), the classifier, the release (T-165), the retry trigger, the reconciler
 * (T-180) and replay (E13) all run their change through this one helper; none locks rows in any other order.
 *
 * <p>Signature seam only - lane C implements it and proves the opposite-order and deadlock scenarios
 * (E9-F2-S1 "Every state change takes the key lock first, in one row order" and "A serialization failure or deadlock
 * is retried in place"). Runs in the caller's tenant context; the body is called inside the caller's open,
 * tenant-bound transaction (R08/R11).
 *
 * <p>Serialization failure ({@code 40001}) or deadlock ({@code 40P01}) is retried in place, up to 3 times with
 * jittered backoff, writing no attempt row; after the third failure the E8-F1-S2 consumer pause applies - the
 * implementation surfaces that as {@link OrderedStateChangeRetryExhaustedException}.
 */
public interface OrderedStateChange {

    /**
     * Runs one state change under the one lock order, with the 40001/40P01 in-place retry.
     *
     * @param orderingKey          the interface's ordering key value, or {@code null}/{@code blank} for an
     *                             {@code ordering: NONE} interface (the advisory lock is skipped)
     * @param messageId            the message whose row is locked second (never {@code null})
     * @param deliveryIdsAscending the affected deliveries, locked third in ascending id order (never {@code null})
     * @param body                 the change itself; every row update inside the body stays a conditional update
     *                             (0 rows = no effect)
     * @param <T>                  the body's result
     * @return the body's result, exactly once (a retried attempt lands exactly once)
     * @throws OrderedStateChangeRetryExhaustedException on the third consecutive 40001/40P01 - the caller applies
     *                                                   the consumer pause
     */
    <T> T run(String orderingKey, UUID messageId, List<UUID> deliveryIdsAscending, Supplier<T> body);
}