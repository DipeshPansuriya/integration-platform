package io.integrationplatform.ingest.internal.relay;

import java.time.Instant;
import java.util.List;

/**
 * The relay's table access, behind a type so the cycle logic is testable without a database (the real
 * {@code FOR UPDATE SKIP LOCKED} claim is {@link JdbcOutboxAccess}; the container test runs the same relay against
 * it twice at once).
 *
 * <p>Contract: both methods run inside the caller's open, tenant-bound transaction - a claim never sees a row
 * outside the bound tenant (tenant predicate + row-level security, R08/R11), a mark never rewrites an existing one.
 */
public interface OutboxRowAccess {

    /**
     * Locks up to {@code limit} unpublished rows of {@code keyBucket} whose {@code available_at <= now} in
     * outbox-id order ({@code FOR UPDATE SKIP LOCKED}), leaving rows another transaction holds to it.
     */
    List<ClaimedOutboxRow> claimUnpublished(int keyBucket, Instant availableAtOrBefore, int limit);

    /**
     * Sets {@code published_at} to the database time for one row.
     *
     * @return {@code false} when 0 rows changed (already marked or gone) - no effect, never an exception
     */
    boolean markPublished(long outboxId);
}
