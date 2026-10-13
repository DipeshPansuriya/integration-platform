package io.integrationplatform.delivery.port;

import java.util.UUID;

/**
 * Instance readiness (E9 seam, arjun-fullstack, F14; stub): a yes/no gate the re-drive paths consult before
 * re-driving work, so E10's readiness rules (T-201) and the reconciler (T-180) slot in without a second edit of
 * the trigger. Signature seam only - the default answers {@code true}, the stub wiring changes no behavior today.
 */
public interface InstanceReadiness {

    /**
     * @return {@code true} when this instance may re-drive the delivery now; {@code false} leaves it for the next
     *     run (the delivery stays in its current state, nothing is written)
     */
    boolean ready(UUID tenantId, UUID deliveryId);

    /** The always-ready stub - today's behavior, exactly. */
    static InstanceReadiness alwaysReady() {
        return (tenantId, deliveryId) -> true;
    }
}