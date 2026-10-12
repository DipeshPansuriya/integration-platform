package io.integrationplatform.domain.delivery;

/**
 * The closed register of attempt outcome classes (ADR-010 section 5). Stored as text with a {@code CHECK} on
 * {@code delivery_attempt}, never as a database ENUM (R10); the constant names are the stored values.
 *
 * <p>E2 seam: the values only. Which status or error maps to which class is the classifier's job.
 */
public enum OutcomeClass {

    /** HTTP 2xx, JMS send acknowledged, DB commit, file renamed into place - the delivery becomes DELIVERED. */
    SUCCESS,

    /**
     * Failed before the request was sent, 429 and 503, and similar - redelivered, then RETRY_WAIT. Also the class of a
     * target-credential key id this instance does not hold (E5 T-185; no call is made, so no attempt row is written).
     */
    TRANSIENT,

    /** The request may have been processed (a timeout after sending) - never redelivered by layer 1. */
    AMBIGUOUS,

    /**
     * 400, 401, 403, 404, 409, 422, mapping or contract failure, refused destination, and a target-credential
     * authentication-tag mismatch (E5 T-185) - never retried.
     */
    PERMANENT
}
