package io.integrationplatform.delivery.port;

import java.util.Optional;
import java.util.UUID;

/**
 * What the delivery.due dispatch must know about one delivery BEFORE it takes a bulkhead permit and claims
 * (E9 lane A2, T-177; ADR-010 section 2 "the permit is taken before the claim", section 7 "the routing
 * consumer derives the same ordering key from the delivery row it read"): the delivery's pinned target
 * endpoint, its ordering key (the {@link io.integrationplatform.delivery.ordering.OrderingKey} record key of
 * the row's scope, {@code null} when the interface's ordering is {@code NONE}) and its current
 * {@code bulkhead_deferrals} count (the consecutive-deferral position the backoff series is read from).
 *
 * <p>Read-only seam (additive port next to {@link BulkheadDeferrals}; no existing signature changes): the
 * implementation runs the read in the caller's tenant-bound context under row-level security and never locks
 * a row - the claim and the conditional deferral stay the only writers. A delivery the tenant cannot see or
 * that is already final answers {@link Optional#empty()} and the record is acknowledged with no effect.
 */
public interface DeliveryBulkheadFacts {

    /** The bulkhead facts of one delivery, or empty when no claimable row is visible to the bound tenant. */
    Optional<BulkheadFacts> forDelivery(UUID deliveryId);

    /**
     * @param targetEndpointId   the delivery's pinned target endpoint (the bulkhead's key)
     * @param orderingKey        the ordering scope's record key, or {@code null} for ordering {@code NONE}
     * @param bulkheadDeferrals  the consecutive bulkhead-full deferrals so far (reset to 0 by a successful
     *                           claim); the next wait in the ADR-010 section 2 series is derived from it
     */
    record BulkheadFacts(UUID targetEndpointId, String orderingKey, int bulkheadDeferrals) {

        public BulkheadFacts {
            if (targetEndpointId == null) {
                throw new IllegalArgumentException("a delivery's pinned target endpoint is never null");
            }
            if (bulkheadDeferrals < 0) {
                throw new IllegalArgumentException("bulkhead_deferrals cannot be negative: " + bulkheadDeferrals);
            }
        }
    }
}
