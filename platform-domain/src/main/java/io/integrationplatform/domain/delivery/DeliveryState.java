package io.integrationplatform.domain.delivery;

/**
 * The closed register of Delivery states (ADR-010 section 6). Stored as text with a {@code CHECK} on
 * {@code delivery.state}, never as a database ENUM (R10); the constant names are the stored values.
 *
 * <p>E2 seam: the values only. Which transitions are legal is lane C's guarded state machine (T-011), not this type.
 */
public enum DeliveryState {

    /** Inserted by routing stage 1; waiting for its first claim. */
    PENDING,

    /** Waiting behind an earlier message or delivery with the same ordering key (ADR-011 Decision 6). */
    HELD,

    /** Claimed by stage 2 with a claim token; one attempt is in flight. */
    SENDING,

    /** Waiting for {@code next_attempt_at} (retry, bulkhead-full or circuit-open deferral, stale claim). */
    RETRY_WAIT,

    /** Ready to be claimed again (re-driven by the delivery-retry job, released from a hold, or replayed). */
    DUE,

    /** Final: the target confirmed the delivery. */
    DELIVERED,

    /** Final until an operator replays it: permanent failure, retries exhausted or an ambiguous outcome. */
    DEAD_LETTERED
}
