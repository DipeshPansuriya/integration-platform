package io.integrationplatform.routing.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E9-F1-S1 / T-116 (amit-developer, lane A1): route templates in {@code platform-routing} are instantiated from
 * configuration rows at publish - on a running system, with 0 deploys and 0 restarts (E9-C1, E9-C2) - and lazily
 * on every consuming instance, removed after 30 minutes idle (ADR-010 section 3). The @negative route side of
 * "Later configuration changes never alter pinned deliveries" (E9-F1-S1): an instantiated route keeps the pinned
 * versions it was built with - only new messages use new versions.
 */
@Tag("e9-t116")
class RouteInstanceFactoryTest {

    private static final UUID TENANT = UUID.nameUUIDFromBytes("tenant-a".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID INTERFACE_ID = UUID.nameUUIDFromBytes("iface-1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID ENDPOINT_A = UUID.nameUUIDFromBytes("endpoint-a".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID ENDPOINT_B = UUID.nameUUIDFromBytes("endpoint-b".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID MAPPING_M3 = UUID.nameUUIDFromBytes("mapping-m3".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID MAPPING_M4 = UUID.nameUUIDFromBytes("mapping-m4".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private DefaultCamelContext context;
    private FakeRouteConfigReader reader;
    private RecordingTemplate template;
    private RouteInstanceFactory factory;

    @BeforeEach
    void aRunningSystem() throws Exception {
        context = new DefaultCamelContext();
        context.start(); // the system is running - the routes join it at runtime
        reader = new FakeRouteConfigReader();
        template = new RecordingTemplate("API");
        factory = new RouteInstanceFactory(reader, context, List.of(template),
                Set.of("https", "kafka", "jms"), Duration.ofMinutes(30),
                () -> Instant.parse("2026-10-12T12:00:00Z"));
    }

    @AfterEach
    void stopTheSystem() throws Exception {
        context.stop();
    }

    @Test
    void publishingAnInterfaceWithTwoTargetsCreatesItsRouteInstancesAtRuntime() {
        reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                null, MAPPING_M3));
        reader.add(new RouteConfigRow(instanceId("route-2"), ENDPOINT_B, "API", "https://target-b.example/v1",
                null, MAPPING_M4));

        List<RouteInstance> instances = factory.publishInterface(TENANT, INTERFACE_ID);

        assertThat(context.isStarted())
                .as("0 deploys, 0 restarts: the already-running context is still the same started instance")
                .isTrue();
        assertThat(instances).hasSize(2);
        assertThat(instances.stream().map(RouteInstance::targetEndpointId).toList())
                .containsExactlyInAnyOrder(ENDPOINT_A, ENDPOINT_B);
        for (RouteInstance instance : instances) {
            assertThat(factory.instanceOf(instance.targetEndpointId())).as("each route is held by the factory").isPresent();
            assertThat(context.getRoute(instance.camelRouteId()))
                    .as("the route instance is live in the running Camel context").isNotNull();
            assertThat(context.getRoute(instance.camelRouteId()).getId()).isEqualTo(instance.camelRouteId());
        }
        assertThat(instances.stream().map(RouteInstance::pinnedMappingVersionId).toList())
                .containsExactlyInAnyOrder(MAPPING_M3, MAPPING_M4);
    }

    @Test
    void laterConfigurationChangesNeverAlterTheInstantiatedRoutesPinnedVersions() {
        RouteConfigRow m3 = new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API",
                "https://target-a.example/v1", null, MAPPING_M3);
        reader.add(m3);
        RouteInstance first = factory.publishInterface(TENANT, INTERFACE_ID).stream().findFirst().orElseThrow();

        reader.replace(m3.routeId(), new RouteConfigRow(m3.routeId(), ENDPOINT_A, "API",
                "https://target-a.example/v2", null, MAPPING_M4));

        RouteInstance afterChange = factory.instanceOf(ENDPOINT_A).orElseThrow();
        assertThat(afterChange.routeId())
                .as("the instance survives the configuration change").isEqualTo(first.routeId());
        assertThat(afterChange.pinnedMappingVersionId())
                .as("an instantiated route stays on its pinned versions; only new messages use m4")
                .isEqualTo(MAPPING_M3);
        RouteInstance ensured = factory.ensure(m3);
        assertThat(ensured.routeId())
                .as("ensure() is the lazy re-use: an already-instantiated endpoint is re-used, never rebuilt")
                .isEqualTo(first.routeId());
    }

    @Test
    void aRouteIdlingPastThirtyMinutesIsRemovedFromTheRunningContextAndRebuiltLazily() {
        reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                null, MAPPING_M3));
        RouteInstance first = factory.publishInterface(TENANT, INTERFACE_ID).stream().findFirst().orElseThrow();
        factory.touch(ENDPOINT_A, Instant.parse("2026-10-12T12:00:00Z"));

        factory.evictIdle(Instant.parse("2026-10-12T12:29:59Z"));
        assertThat(factory.instanceOf(ENDPOINT_A))
                .as("29 minutes 59 seconds idle: still inside the 30-minute window").isPresent();

        factory.evictIdle(Instant.parse("2026-10-12T12:30:01Z"));
        assertThat(factory.instanceOf(ENDPOINT_A))
                .as("30 minutes idle removes the route instance (ADR-010 section 3)")
                .isEmpty();
        assertThat(context.getRoute(first.camelRouteId())).as("the idle route is gone from the context").isNull();

        RouteInstance rebuilt = factory.ensure(reader.current(ENDPOINT_A));
        assertThat(rebuilt.routeId()).as("lazy rebuild keeps the configuration row's route identity")
                .isEqualTo(first.routeId());
        assertThat(context.getRoute(rebuilt.camelRouteId()))
                .as("the next delivery lazily rebuilds the route on this consuming instance").isNotNull();
        assertThat(template.builds).isEqualTo(2).as("the rebuild went through the template again");
    }

    @Test
    void publishIsIdempotentAndNeverRebuildsARouteThatIsAlreadyInstantiated() {
        reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                null, MAPPING_M3));
        factory.publishInterface(TENANT, INTERFACE_ID);
        factory.publishInterface(TENANT, INTERFACE_ID);

        assertThat(template.builds).isEqualTo(1)
                .as("a second publish of the same rows creates no second build (lazy re-use)");
    }

    @Test
    void anUnapprovedSchemeIsRefusedAndThePublishFailsWithAClearError() {
        reader.add(new RouteConfigRow(instanceId("route-3"), ENDPOINT_A, "API", "exec:/bin/echo", null, MAPPING_M3));

        assertThatThrownBy(() -> factory.publishInterface(TENANT, INTERFACE_ID))
                .as("T-120 machinery at route build: configuration cannot reach an unapproved component")
                .isInstanceOf(RouteBuildRefusedException.class)
                .hasMessageContaining("exec:");
        assertThat(factory.instanceOf(ENDPOINT_A)).as("the refused route is never registered").isEmpty();
    }

    private UUID instanceId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }


    /** Reads the configuration rows the running system holds (the port publish and lazy creation read through). */
    static final class FakeRouteConfigReader implements RouteConfigReader {

        final Map<UUID, RouteConfigRow> rows = new LinkedHashMap<>();

        void add(RouteConfigRow row) {
            rows.put(row.routeId(), row);
        }

        void replace(UUID routeId, RouteConfigRow row) {
            rows.remove(routeId);
            add(row);
        }

        RouteConfigRow current(UUID targetEndpointId) {
            return rows.values().stream().filter(r -> r.targetEndpointId().equals(targetEndpointId)).findFirst()
                    .orElseThrow();
        }

        @Override
        public List<RouteConfigRow> activeRoutes(UUID tenantId, UUID interfaceId) {
            return List.copyOf(rows.values());
        }
    }

    /** The template under test: it supplies the instance's send behaviour and counts its builds. */
    static final class RecordingTemplate implements RouteTemplate {

        private final String type;
        int builds;

        RecordingTemplate(String type) {
            this.type = type;
        }

        @Override
        public String targetType() {
            return type;
        }

        @Override
        public org.apache.camel.Processor build(RouteConfigRow row) {
            builds++;
            return exchange -> { }; // wave 0: the adapters (E10) own real send behaviour
        }
    }
}