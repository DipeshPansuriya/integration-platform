package io.integrationplatform.ingest.internal.dedup;

/**
 * T-010 (E2-F1): the consumer-side duplicate guard. Kafka is at-least-once (a publisher that crashes after send and
 * before marking re-publishes the same outbox row), so every consumer of an {@link
 * io.integrationplatform.ingest.port.OutboundRecord} asks this guard before acting on it. The key is the record's
 * frozen {@code idempotencyKey()} - stable per outbox row, different for every row.
 *
 * <p>Asking the guard CLAIMS the key inside the caller's transaction (ADR-001): the claim commits or rolls back with
 * the consumer's work, so a consumer that dies mid-handling leaves no claim and the re-delivery is handled again
 * exactly once. {@link #isDuplicate(String)} answers for committed claims only.
 */
public interface DuplicateGuard {

    /**
     * Claims {@code idempotencyKey} for the bound tenant and answers: {@code false} the first time (the record
     * passes), {@code true} for every later ask with the same committed key (the record is a duplicate - acknowledge
     * it and change nothing).
     *
     * @param idempotencyKey the {@code OutboundRecord.idempotencyKey()} of the record being consumed
     * @throws io.integrationplatform.tenancy.NoTenantBoundException if no tenant is bound (fails closed, ADR-007)
     * @throws IllegalStateException if called outside a transaction (R11)
     * @throws IllegalArgumentException if the key is null or blank
     */
    boolean isDuplicate(String idempotencyKey);
}
