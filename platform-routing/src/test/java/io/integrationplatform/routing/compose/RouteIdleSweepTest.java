package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteInstance;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.routing.templates.RouteTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.Processor;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** row278 D3/F16: the idle sweep runs evictIdle on its own, so an idle route instance leaves without traffic. */
class RouteIdleSweepTest {

    private static final UUID ENDPOINT = UUID.nameUUIDFromBytes("endpoint-a".getBytes(StandardCharsets.UTF_8));
    private static final Instant BUILT = Instant.parse("2026-10-12T12:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(BUILT);
    private DefaultCamelContext context;
    private RouteInstanceFactory factory;

    @BeforeEach
    void aRunningSystem() {
        context = new DefaultCamelContext();
        context.start();
        RouteTemplate api = new RouteTemplate() {
            @Override
            public String targetType() {
                return "API";
            }

            @Override
            public Processor build(RouteConfigRow row) {
                return exchange -> { };
            }
        };
        factory = new RouteInstanceFactory((tenantId, interfaceId) -> List.of(), context, List.of(api), Set.of("https"),
                Duration.ofMinutes(30), now::get);
    }

    @AfterEach
    void stop() {
        context.stop();
    }

    @Test
    void aSweepRemovesTheInstanceIdlePastThirtyMinutesAndKeepsAFreshOne() {
        RouteInstance instance = factory.ensure(new RouteConfigRow(UUID.randomUUID(), ENDPOINT, "API",
                "https://target-a.example/v1", null, UUID.randomUUID()));
        RouteIdleSweep sweep = new RouteIdleSweep(factory, now::get, Duration.ofMinutes(1));

        now.set(Instant.parse("2026-10-12T12:29:00Z"));
        sweep.sweepOnce();
        assertThat(context.getRoute(instance.camelRouteId())).as("29 minutes idle: stays").isNotNull();

        now.set(Instant.parse("2026-10-12T12:31:00Z"));
        sweep.sweepOnce();
        assertThat(context.getRoute(instance.camelRouteId())).as("31 minutes idle: the sweep removed it").isNull();
        assertThat(factory.instanceOf(ENDPOINT)).isEmpty();
    }

    @Test
    void theSweepRunsOnItsOwnThreadUntilStopped() throws Exception {
        factory.ensure(new RouteConfigRow(UUID.randomUUID(), ENDPOINT, "API", "https://target-a.example/v1", null,
                UUID.randomUUID()));
        now.set(Instant.parse("2026-10-12T13:00:00Z"));
        RouteIdleSweep sweep = new RouteIdleSweep(factory, now::get, Duration.ofMillis(20));

        sweep.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (factory.instanceOf(ENDPOINT).isPresent() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(sweep.isRunning()).isTrue();
        } finally {
            sweep.stop();
        }
        assertThat(factory.instanceOf(ENDPOINT)).as("the running sweep evicted the idle instance").isEmpty();
        assertThat(sweep.isRunning()).isFalse();
    }
}
