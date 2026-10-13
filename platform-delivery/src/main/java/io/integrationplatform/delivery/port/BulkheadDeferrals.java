package io.integrationplatform.delivery.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Bulkhead-full deferrals (E9 seam, arjun-fullstack, F14): the conditional deferral update lane A's
 * bulkhead-full path (T-198) and lane C's implementation share. Signature seam only; every method runs in the
 * caller's open, tenant-bound transaction (R08/R11) and keeps 0-rows-means-no-effect as a {@code false}, never an
 * exception.
 */
public interface BulkheadDeferrals {

    /**
     * Defers a delivery whose target's bulkhead is full: leaves it {@code RETRY_WAIT} (or keeps it PENDING/DUE per
     * the bulkhead path's choice) with {@code next_attempt_at} from the ADR-010 section 2 backoff series - 1 s
     * doubling per consecutive deferral of this delivery, capped at 30 s, full jitter - and writes NO attempt row.
     * The update is conditional on {@code status IN ('PENDING','DUE')} and takes the ordering key first (via
     * {@link OrderedStateChange}).
     *
     * @param deliveryId           the blocked delivery
     * @param now                  this deferral's instant
     * @param consecutiveDeferrals how many consecutive bulkhead-full deferrals this delivery has already had
     *                             (a successful claim resets the count to 0); 0 means this is the first
     * @param jitter               the full-jitter fraction in [0, 1], drawn by the caller
     * @return {@code false} when 0 rows changed - the delivery is no longer PENDING or DUE (claimed or moved by
     *     another transaction): acknowledged, no effect
     */
    boolean defer(UUID deliveryId, Instant now, int consecutiveDeferrals, double jitter);
}