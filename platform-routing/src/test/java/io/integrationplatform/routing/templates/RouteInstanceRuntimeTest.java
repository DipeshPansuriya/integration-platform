package io.integrationplatform.routing.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RouteInstanceRuntimeTest {

    private static final class FakeReader implements RouteConfigReader {
        @Override
        public List<RouteConfigRow> activeRoutes(UUID tenantId, UUID interfaceId) {
            return List.of();
        }
    }

    private RouteInstanceRuntime runtime;

    @AfterEach
    void stop() throws Exception {
        if (runtime != null) {
            runtime.stop();
        }
    }

    @Test
    void startsAndStopsTheCamelCore() throws Exception {
        RouteTemplate api = new RouteTemplate() {
            @Override
            public String targetType() {
                return "API";
            }

            @Override
            public org.apache.camel.Processor build(RouteConfigRow row) {
                return exchange -> { };
            }
        };
        runtime = new RouteInstanceRuntime(new FakeReader(), List.of(api), Set.of("https"));

        assertThat(runtime.isRunning()).isFalse();
        runtime.start();
        assertThat(runtime.isRunning()).isTrue();
        assertThat(runtime.context().isStarted()).isTrue();
        assertThat(runtime.factory()).isNotNull();
        runtime.stop();
        assertThat(runtime.isRunning()).isFalse();
    }

    @Test
    void isIdempotentOnRepeatedStart() throws Exception {
        RouteTemplate api = new RouteTemplate() {
            @Override
            public String targetType() {
                return "API";
            }

            @Override
            public org.apache.camel.Processor build(RouteConfigRow row) {
                return exchange -> { };
            }
        };
        runtime = new RouteInstanceRuntime(new FakeReader(), List.of(api), Set.of("https"));

        runtime.start();
        runtime.start();
        assertThat(runtime.isRunning()).isTrue();
        runtime.stop();
        runtime.stop();
        assertThat(runtime.isRunning()).isFalse();
    }

    @Test
    void theFactoryBuildsARouteOnTheStartedContext() throws Exception {
        RouteTemplate api = new RouteTemplate() {
            @Override
            public String targetType() {
                return "API";
            }

            @Override
            public org.apache.camel.Processor build(RouteConfigRow row) {
                return exchange -> { };
            }
        };
        runtime = new RouteInstanceRuntime(new FakeReader(), List.of(api), Set.of("https"));
        runtime.start();
        RouteConfigRow row = new RouteConfigRow(UUID.randomUUID(), UUID.randomUUID(), "API",
                "https://target.example/v1", null, UUID.randomUUID());

        RouteInstance instance = runtime.factory().ensure(java.util.UUID.fromString("00000000-0000-4000-8000-0000000000aa"), row);

        assertThat(runtime.context().getRoute(instance.camelRouteId()))
                .as("the route instance is live on the runtime's own started context").isNotNull();
    }

    @Test
    void concurrentStartsAndStopsLeaveOneConsistentState() throws Exception {
        runtime = new RouteInstanceRuntime(new FakeReader(), List.of(), Set.of("https"));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<?>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(pool.submit(() -> {
                    runtime.start();
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> call : calls) {
                call.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(runtime.isRunning()).isTrue();
            calls.clear();
            for (int i = 0; i < 8; i++) {
                calls.add(pool.submit(() -> {
                    runtime.stop();
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> call : calls) {
                call.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(runtime.isRunning()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }
}
