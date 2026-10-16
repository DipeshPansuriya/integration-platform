package io.integrationplatform.routing.stage2.bulkhead;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The instance's per-target bulkheads (T-177, ADR-010 section 2): one {@link TargetBulkhead} per
 * (target endpoint), created on first use - the pool for a target exists while that target has work on this
 * instance. Deliberately per instance (like the route instances themselves): never broadcast, never shared
 * across instances; the effective limit is the pool size times the instance count.
 */
public final class BulkheadRegistry {

    private final BulkheadSettings settings;
    private final Map<UUID, TargetBulkhead> byTarget = new ConcurrentHashMap<>();

    public BulkheadRegistry(BulkheadSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    BulkheadRegistry(int maxConcurrentSends) {
        this(new BulkheadSettings(maxConcurrentSends));
    }

    /** This target's bounded pool, created on first use. */
    public TargetBulkhead of(UUID targetEndpointId) {
        return byTarget.computeIfAbsent(Objects.requireNonNull(targetEndpointId, "targetEndpointId"),
                target -> new TargetBulkhead(settings, target));
    }

    /** Instance shutdown: stop accepting new work on every pool. */
    public void shutdown() {
        byTarget.values().forEach(TargetBulkhead::shutdown);
    }
}
