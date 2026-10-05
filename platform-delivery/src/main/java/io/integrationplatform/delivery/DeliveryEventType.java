package io.integrationplatform.delivery;

/**
 * The register of {@code delivery_event} kinds (T-012, T-193): the named transitions and deferrals of ADR-010
 * sections 5 to 7. Stored as text with a {@code CHECK} (R10); the constant names are the stored values.
 *
 * <p>{@code LATE_OUTCOME} records the outcome of a claim that was already declared stale or belongs to another
 * attempt (ADR-010 section 6): its attempt row was written, the event names why the state did not move.
 */
public enum DeliveryEventType {

    /** A claim moved the delivery to {@code SENDING} with a fresh token (PENDING or DUE -&gt; SENDING). */
    CLAIM,

    /** The claim's hold check moved the delivery to {@code HELD} (ADR-011 Decision 6). */
    HELD,

    /** The blockers are gone: {@code HELD -> DUE}. */
    RELEASE,

    /** An attempt ended for a retryable reason: {@code SENDING -> RETRY_WAIT} with the new retry time. */
    RETRY,

    /** The retry time was reached: {@code RETRY_WAIT -> DUE}. */
    REDRIVE,

    /** {@code SENDING -> DELIVERED}. */
    DELIVER,

    /** {@code SENDING -> DEAD_LETTERED}, or a permanent failure never retried. */
    DEAD_LETTER,

    /** An operator replay: {@code DEAD_LETTERED -> DUE} (audited elsewhere). */
    REPLAY,

    /** Deferred without a call: the target's bulkhead was full (ADR-010 section 2). */
    BULKHEAD_FULL,

    /** Deferred without a call and without an attempt: the credential key is not on this instance. */
    KEY_UNAVAILABLE,

    /** Deferred without a call: the target's circuit is open. */
    CIRCUIT_DEFER,

    /** The reconciler re-drove a delivery nobody moved (ADR-010 section 9). */
    RECONCILE,

    /** The outcome of a stale or foreign claim: attempt written, event recorded, state unchanged. */
    LATE_OUTCOME
}
