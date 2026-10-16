package io.integrationplatform.routing.stage2.bulkhead;

/**
 * The typed bulkhead setting (T-177, ADR-010 section 2): the pool size is the target setting
 * {@code max_concurrent_sends} - team default 4, validated 1-32 - with queue length fixed at 0 (the bulkhead
 * never hides work in an in-memory queue; a full pool defers through {@link
 * io.integrationplatform.delivery.port.BulkheadDeferrals}). The value itself arrives from configuration
 * ({@code routing.core.stage2.bulkhead.max-concurrent-sends}, an environment variable, R07 - never a figure
 * written in a file).
 */
public record BulkheadSettings(int maxConcurrentSends) {

    public BulkheadSettings {
        if (maxConcurrentSends < 1 || maxConcurrentSends > 32) {
            throw new IllegalArgumentException(
                    "max_concurrent_sends must be 1..32 (ADR-010 section 2): " + maxConcurrentSends);
        }
    }

    /** The team default of ADR-010 section 2. */
    public static BulkheadSettings teamDefault() {
        return new BulkheadSettings(4);
    }
}
