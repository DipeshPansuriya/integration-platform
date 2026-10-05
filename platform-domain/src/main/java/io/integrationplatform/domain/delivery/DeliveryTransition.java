package io.integrationplatform.domain.delivery;

import java.time.Instant;
import java.util.Objects;

/**
 * One recorded state transition of a Delivery (T-011, ADR-010 section 6): from, to and the time it happened.
 * The repository writes each of these to {@code delivery_event} (T-012), so "each transition is stored with its
 * time" (E2-F2-S1) holds in the domain ledger before it holds in the database.
 */
public record DeliveryTransition(DeliveryState from, DeliveryState to, Instant at) {

    public DeliveryTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(at, "at");
    }
}
