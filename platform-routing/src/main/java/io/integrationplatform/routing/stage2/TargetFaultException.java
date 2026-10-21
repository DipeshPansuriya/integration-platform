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
    public static class TransientFaultException extends TargetFaultException {

        public TransientFaultException(String message) {
            super(OutcomeClass.TRANSIENT, message);
        }
    }

    /**
     * E10 T96 (ADR-010 sections 5 and 6): the target's circuit breaker refused the call - nothing was sent. Not a
     * call, so layer 1 neither redelivers it nor writes an attempt row; the delivery is deferred once (a
     * {@code CIRCUIT_DEFER} event) until the breaker may let a trial call through, {@code retryAfter} from now.
     */
    public static final class CircuitOpenException extends TransientFaultException {

        private final java.time.Duration retryAfter;

        public CircuitOpenException(String message, java.time.Duration retryAfter) {
            super(message);
            this.retryAfter = java.util.Objects.requireNonNull(retryAfter, "retryAfter");
        }

        /** How long from now the breaker stays closed to calls (its open-state wait). */
        public java.time.Duration retryAfter() {
            return retryAfter;
        }

        /** The refusal of {@code breaker}: its message names the state, the wait is the configured open-state wait. */
        public static CircuitOpenException of(io.github.resilience4j.circuitbreaker.CircuitBreaker breaker,
                Object targetEndpointId) {
            return new CircuitOpenException("the breaker of target endpoint " + targetEndpointId + " is "
                    + breaker.getState().name().toLowerCase(java.util.Locale.ROOT) + " - nothing sent; the delivery waits",
                    java.time.Duration.ofMillis(
                    breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1)));
        }
    }
}
