package io.integrationplatform.delivery.port;

import java.util.Optional;
import java.util.UUID;

/**
 * T-183 (ADR-010 section 6): which deliveries are waiting because their target's circuit was open. The delivery-retry
 * trigger caps the re-drive of exactly these per target at the breaker's trial-call count ({@link TargetHalfOpenCap});
 * a delivery waiting for any other reason is not counted. Read-only, in the caller's tenant-bound transaction.
 */
@FunctionalInterface
public interface DeliveryCircuitFacts {

    /** The target endpoint whose open circuit deferred {@code deliveryId}, or empty when it was not circuit-deferred. */
    Optional<UUID> circuitDeferredTarget(UUID deliveryId);

    /** Nothing is known to be circuit-deferred: no half-open cap applies (the behaviour before T-183). */
    static DeliveryCircuitFacts none() {
        return delivery -> Optional.empty();
    }
}
