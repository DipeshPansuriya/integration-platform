package io.integrationplatform.delivery.internal.deferral;

import java.time.Duration;

/** T-198 RED stub: the ADR-010 section 2 bulkhead-full backoff series (body lands in the GREEN commit). */
public final class BulkheadBackoff {

    private BulkheadBackoff() {
    }

    public static long seriesSeconds(int consecutiveDeferrals) {
        throw new UnsupportedOperationException("T-198 RED stub");
    }

    public static Duration waitDuration(int consecutiveDeferrals, double jitter) {
        throw new UnsupportedOperationException("T-198 RED stub");
    }
}
