package io.integrationplatform.ingest.port;

/**
 * A known, refused intake - nothing was persisted (E11 A1 narrow). Each reason maps to one HTTP answer in the
 * adapter ({@code IntakeController}): 404/422/400 classes of ADR-011 Decision 4's response table. Refusals are
 * not a database failure: a refused transaction must commit nothing and answer immediately.
 */
public class IntakeRefusedException extends RuntimeException {

    public enum Reason {
        /** The interface code does not exist for the bound tenant, or is not PUBLISHED - answered 404. */
        INTERFACE_NOT_FOUND_OR_NOT_PUBLISHED,
        /** The sent {@code X-Source-Contract-Version} does not match the interface's pinned contract version - 422. */
        CONTRACT_PIN_MISMATCH,
        /** The business key rule could not resolve from the payload - 422. */
        BUSINESS_KEY_UNRESOLVED,
        /** The business key is over the intake key cap (BS-06.4, row 375: an over-bound key cannot ride the
         * message_store's btree indexes) - 422 with the cap named, never the catch-all 503. */
        BUSINESS_KEY_TOO_LONG,
        /** The Idempotency-Key was already used by this credential with another request body (T-187) - 422. */
        IDEMPOTENCY_KEY_REUSED
    }

    private final Reason reason;

    public IntakeRefusedException(Reason reason, String detail) {
        super(detail);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    /** The human-readable detail the adapter puts in the error body (the pinned label for a pin mismatch). */
    public String detail() {
        return getMessage();
    }
}