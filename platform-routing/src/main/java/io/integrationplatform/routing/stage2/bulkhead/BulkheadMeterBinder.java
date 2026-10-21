package io.integrationplatform.routing.stage2.bulkhead;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mahesh 1g (ADR-010 section 8's per-target metric set): the bulkhead's two required rows - the
 * {@code platform.delivery.bulkhead.in_use} gauge (permits in hand per target right now) and the
 * {@code platform.delivery.bulkhead.full} counter (+1 per BULKHEAD_FULL deferral, which is a delivery event, NO
 * attempt row and NO attempt used up). A Spring MeterBinder, so the composed app's meters pick it up without a
 * compose edit (compose is not this lane's). Targets appear lazily, so binding hooks FIRST-BY-CREATION through
 * {@link BulkheadRegistry#onTargetCreated(java.util.function.Consumer)}.
 */
public final class BulkheadMeterBinder implements MeterBinder {

    private final BulkheadRegistry bulkheads;
    private final AtomicBoolean bound = new AtomicBoolean();

    public BulkheadMeterBinder(BulkheadRegistry bulkheads) {
        this.bulkheads = bulkheads;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (!bound.compareAndSet(false, true)) {
            return; // one binding per JVM
        }
        bulkheads.onTargetCreated(pool -> register(registry, pool));
    }

    /**
     * Registrations also stand for pools created between the compose wiring and this binder's bindTo: every
     * TargetBulkhead is created through {@link BulkheadRegistry#of} inside it, and the binder is bound at
     * refresh, before any consumer runs. The per-pool deferral count is cumulative since pool creation
     * (one pool per instance per target; the metric notes the instance through the usual instance tag).
     */
    private static void register(MeterRegistry registry, TargetBulkhead pool) {
        String target = pool.targetEndpointId().toString();
        List<Tag> tags = List.of(Tag.of("target", target));
        FunctionCounter.builder("platform.delivery.bulkhead.full", pool, TargetBulkhead::deferrals)
                .tags(tags).description("BULKHEAD_FULL deferrals of this target's bulkhead on this instance "
                        + "+1 each, no attempt row, no attempt used up").register(registry);
        Gauge.builder("platform.delivery.bulkhead.in_use", pool, p -> p.inUse())
                .tags(tags).description("permits in hand on this target's bulkhead (pool size "
                        + "max_concurrent_sends)").register(registry);
    }

    /** The wiring: one binder on the composed registry (this module's scan picks the configuration). */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class Wiring {

        @org.springframework.context.annotation.Bean
        BulkheadMeterBinder bulkheadMeterBinder(BulkheadRegistry bulkheadRegistry) {
            return new BulkheadMeterBinder(bulkheadRegistry);
        }
    }
}