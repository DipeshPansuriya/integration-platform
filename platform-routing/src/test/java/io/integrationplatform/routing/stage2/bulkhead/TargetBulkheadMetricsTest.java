package io.integrationplatform.routing.stage2.bulkhead;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mahesh 1g (ADR-010 section 8): the bulkhead's two metric rows - the in-use gauge (permits in hand) and the
 * BULKHEAD_FULL counter (+1 per deferral, never an attempt row) - tagged per target on this instance's pool. The
 * binder hooks first-creation, so compose's zero-arg wiring stays. Unit level: a SimpleMeterRegistry. Not tagged
 * container (R03).
 */
class TargetBulkheadMetricsTest {

    private final UUID target = UUID.randomUUID();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    @DisplayName("Gauge + counter register on the pool's first use after the binder bound")
    void firstUseRegistersTheRows() {
        BulkheadRegistry bulkheads = new BulkheadRegistry(new BulkheadSettings(2));
        new BulkheadMeterBinder(bulkheads).bindTo(meters);

        TargetBulkhead pool = bulkheads.of(target);

        assertThat(pool.inUse()).isZero();
        assertThat(meters.get("platform.delivery.bulkhead.in_use").tag("target", target.toString()).gauge().value())
                .isZero();
        assertThat(meters.get("platform.delivery.bulkhead.full").tag("target", target.toString())
                .functionCounter().count()).isZero();

        assertThat(pool.bound()).as("the pool's bound is max_concurrent_sends").isEqualTo(2);
    }

    @Test
    @DisplayName("Taking the last permit moves the gauge; a failed take moves the counter, not the gauge")
    void gaugeCountsPermitsInHandAndCounterCountsDeferrals() {
        BulkheadRegistry bulkheads = new BulkheadRegistry(new BulkheadSettings(2));
        new BulkheadMeterBinder(bulkheads).bindTo(meters);
        TargetBulkhead pool = bulkheads.of(target);

        assertThat(pool.tryAcquire()).isTrue();
        assertThat(pool.tryAcquire()).isTrue();
        assertThat(pool.inUse()).isEqualTo(2);
        assertThat(pool.tryAcquire()).as("the bulkhead is full: defer").isFalse();

        assertThat(meters.get("platform.delivery.bulkhead.in_use").gauge().value()).isEqualTo(2);
        assertThat(meters.get("platform.delivery.bulkhead.full").functionCounter().count()).isEqualTo(1);
        assertThat(meters.get("platform.delivery.bulkhead.full").functionCounter().count())
                .as("the count tracks the pool's own total").isEqualTo(pool.deferrals());
    }

    @Test
    @DisplayName("Permit returned: the gauge falls back; the deferral count never forgets")
    void releaseReturnsTheGaugeNotTheCounter() {
        BulkheadRegistry bulkheads = new BulkheadRegistry(new BulkheadSettings(1));
        new BulkheadMeterBinder(bulkheads).bindTo(meters);
        TargetBulkhead pool = bulkheads.of(target);
        assertThat(pool.tryAcquire()).isTrue();
        assertThat(pool.tryAcquire()).isFalse();

        pool.release();

        assertThat(pool.inUse()).isZero();
        assertThat(meters.get("platform.delivery.bulkhead.in_use").gauge().value()).isZero();
        assertThat(meters.get("platform.delivery.bulkhead.full").functionCounter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a pool created before the binder bound stays unregistered - the hook is creation-time")
    void poolsBeforeBindingAreStillTold() {
        BulkheadRegistry bulkheads = new BulkheadRegistry(new BulkheadSettings(1));
        TargetBulkhead early = bulkheads.of(target);
        assertThat(poolIn(meters)).as("nothing bound yet: no row").isNull();

        new BulkheadMeterBinder(bulkheads).bindTo(meters);
        assertThat(poolIn(meters)).as("binding retroactively registered nothing (hook is creation-time)")
                .isNull();
        assertThat(bulkheads.of(target)).isSameAs(early);
    }

    private io.micrometer.core.instrument.FunctionCounter poolIn(SimpleMeterRegistry registry) {
        return registry.find("platform.delivery.bulkhead.full").functionCounter();
    }
}