package io.integrationplatform.delivery.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * T-180 (E9-F3-S1, per ADR fix m-817272 S-b): the delivery side of the reconciler sweep. Every method runs in the
 * caller's open, tenant-bound transaction and sees only the bound tenant's rows; a state change is conditional
 * (0 rows means {@code false}, never an exception - ADR-010 section 7).
 */
public interface DeliveryReconcileStore {

    /** A {@code SENDING} claim: the token any outcome must carry, when it was taken, its target, its attempts. */
    record SendingClaim(UUID deliveryId, UUID claimToken, Instant claimedAt, UUID targetId, int attempts) {
    }

    /** {@code SENDING} deliveries of the bound tenant claimed at or before {@code claimedBefore}, oldest first. */
    List<SendingClaim> sendingClaimedBefore(Instant claimedBefore, int limit);

    /**
     * {@code HELD} deliveries of the bound tenant whose earlier same-scope (interface, business key, target)
     * deliveries are all final - the release a final state should have made (ADR-011 Decision 6) - locked
     * {@code FOR UPDATE SKIP LOCKED}, in key-sequence order.
     */
    List<UUID> lockReleasableHeld(int limit);

    /** {@code HELD -> DUE} with the {@code RELEASE} event; {@code false} when the row is no longer held. */
    boolean release(UUID deliveryId);

    /** The no-op sweep: the trigger's constructors without a reconcile store reconcile nothing. */
    static DeliveryReconcileStore none() {
        return new DeliveryReconcileStore() {
            @Override
            public List<SendingClaim> sendingClaimedBefore(Instant claimedBefore, int limit) {
                return List.of();
            }

            @Override
            public List<UUID> lockReleasableHeld(int limit) {
                return List.of();
            }

            @Override
            public boolean release(UUID deliveryId) {
                return false;
            }
        };
    }
}
