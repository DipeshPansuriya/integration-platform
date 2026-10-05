package io.integrationplatform.domain.delivery;

/**
 * Thrown when a transition outside the register of ADR-010 section 6 is attempted (T-011): the delivery keeps
 * its state and no transition is recorded. A database write is never reached.
 */
public class IllegalDeliveryTransitionException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    IllegalDeliveryTransitionException(DeliveryState from, DeliveryState to) {
        super("transition %s -> %s is not in the register of ADR-010 section 6".formatted(from, to));
    }
}
