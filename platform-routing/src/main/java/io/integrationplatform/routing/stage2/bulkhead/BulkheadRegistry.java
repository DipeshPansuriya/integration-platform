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
    private volatile java.util.function.Consumer<TargetBulkhead> onCreated = pool -> {
    };

    public BulkheadRegistry(BulkheadSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Mahesh 1g: the metric binder hooks pool creation this way - compose's zero-arg wiring stays untouched. */
    public void onTargetCreated(java.util.function.Consumer<TargetBulkhead> consumer) {
        this.onCreated = Objects.requireNonNull(consumer, "consumer");
    }

    BulkheadRegistry(int maxConcurrentSends) {
        this(new BulkheadSettings(maxConcurrentSends));
    }

    /** This target's bounded pool, created on first use - the metric binder is told on first creation only. */
    public TargetBulkhead of(UUID targetEndpointId) {
        return byTarget.computeIfAbsent(Objects.requireNonNull(targetEndpointId, "targetEndpointId"), target -> {
            TargetBulkhead pool = new TargetBulkhead(settings, target);
            onCreated.accept(pool);
            return pool;
        });
    }

    /** Instance shutdown: stop accepting new work on every pool. */
    public void shutdown() {
        byTarget.values().forEach(TargetBulkhead::shutdown);
    }
}
