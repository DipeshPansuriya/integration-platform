package io.integrationplatform.delivery.replay;

import io.integrationplatform.domain.delivery.DeliveryState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * E13 N2 (T111, T-166 narrow): what the operator retry and replay actions need from the delivery ledger. Every call
 * runs in the caller's open, tenant-stamped transaction and sees the bound tenant's rows only (R08/R11) - a delivery
 * of another tenant is simply absent. Implemented on the real tables in {@code delivery.internal.replay}.
 */
public interface OperatorActionStore {

    /** The delivery an operator acts on: its current state and the versions it was created with (its pins). */
    record Target(UUID deliveryId, DeliveryState state, UUID mappingVersionId, UUID contractVersionId,
            UUID targetEndpointId) {

        public Target(UUID deliveryId, DeliveryState state, UUID mappingVersionId, UUID contractVersionId) {
            this(deliveryId, state, mappingVersionId, contractVersionId, null);
        }
    }

    /** The bound tenant's delivery, locked {@code FOR UPDATE} in the caller's transaction; empty when not visible. */
    Optional<Target> lockForAction(UUID deliveryId);

    /**
     * T172: writes the contract pin of a delivery whose route has no mapping, only while it is still NULL; false
     * when the row already holds a pin (or is not visible).
     */
    boolean pinContract(UUID deliveryId, UUID contractVersionId);

    /** {@code DEAD_LETTERED -> DUE} with its {@code REPLAY} event; false when the row was not dead-lettered. */
    boolean replay(UUID deliveryId, Instant at);

    /** {@code RETRY_WAIT -> DUE} now, with its {@code REDRIVE} event; false when the row was not waiting. */
    boolean retryNow(UUID deliveryId, Instant at);
}
