package io.integrationplatform.ingest.internal.relay;

import java.time.Instant;

/**
 * The bound tenant's publish backlog (T-191; ADR-005 Decision 12 observability): how many due rows are still
 * unpublished and since when the oldest of them has been due - the inputs of the unpublished-rows and
 * oldest-age gauges and of the outbox-age alert.
 *
 * @param unpublishedRows due rows ({@code available_at <= now}) without {@code published_at}
 * @param oldestDueAt     the oldest such row's {@code available_at}, or {@code null} when there is none
 */
public record OutboxBacklog(long unpublishedRows, Instant oldestDueAt) {

    /** No due unpublished row. */
    public static final OutboxBacklog NONE = new OutboxBacklog(0, null);
}
