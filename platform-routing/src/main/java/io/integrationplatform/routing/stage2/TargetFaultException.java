package io.integrationplatform.routing.stage2;

import io.integrationplatform.domain.delivery.OutcomeClass;

/**
 * One target call's failure as the layer-1 route sees it (T-119): the outcome class is decided ONCE by the
 * classifier ({@code OutcomeClassifier}, platform-domain) - never a naked exception mapped by Camel. Redelivery
 * follows the class (ADR-010 section 5): {@code TRANSIENT} redelivers; {@code AMBIGUOUS} and {@code PERMANENT}
 * never do.
 */
public class TargetFaultException extends RuntimeException {

    private final OutcomeClass outcomeClass;

    public TargetFaultException(OutcomeClass outcomeClass, String message) {
        super(message);
        this.outcomeClass = outcomeClass != null ? outcomeClass : OutcomeClass.AMBIGUOUS;
    }

    /** The classified outcome of the failed call (the register of ADR-010 section 5). */
    public OutcomeClass outcomeClass() {
        return outcomeClass;
    }

    /** The redelivering subclass: only a {@code TRANSIENT} call failure may be re-attempted inside the attempt. */
    public static final class TransientFaultException extends TargetFaultException {

        public TransientFaultException(String message) {
            super(OutcomeClass.TRANSIENT, message);
        }
    }
}