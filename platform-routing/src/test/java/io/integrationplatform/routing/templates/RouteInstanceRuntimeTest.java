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
}
