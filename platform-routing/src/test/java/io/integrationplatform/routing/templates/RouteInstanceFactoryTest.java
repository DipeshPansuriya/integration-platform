package io.integrationplatform.routing.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    void aPublishedInterfaceWithTwoTargetsGetsItsRouteInstancesAtRuntimeOnFirstUse() {
        reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                null, MAPPING_M3));
        reader.add(new RouteConfigRow(instanceId("route-2"), ENDPOINT_B, "API", "https://target-b.example/v1",
                null, MAPPING_M4));

        factory.publishInterface(TENANT, INTERFACE_ID); // AC-1: validates only
        List<RouteInstance> instances = reader.rows.values().stream().map(row -> factory.ensure(TENANT, row)).toList(); // stage 2, lazily

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
        factory.publishInterface(TENANT, INTERFACE_ID);
        RouteInstance first = factory.ensure(TENANT, m3); // built lazily in stage 2 with the row's pinned versions

        reader.replace(m3.routeId(), new RouteConfigRow(m3.routeId(), ENDPOINT_A, "API",
                "https://target-a.example/v2", null, MAPPING_M4));

        RouteInstance afterChange = factory.instanceOf(ENDPOINT_A).orElseThrow();
        assertThat(afterChange.routeId())
                .as("the instance survives the configuration change").isEqualTo(first.routeId());
        assertThat(afterChange.pinnedMappingVersionId())
                .as("an instantiated route stays on its pinned versions; only new messages use m4")
                .isEqualTo(MAPPING_M3);
        RouteInstance ensured = factory.ensure(TENANT, m3);
        assertThat(ensured.routeId())
                .as("ensure() is the lazy re-use: an already-instantiated endpoint is re-used, never rebuilt")
                .isEqualTo(first.routeId());
    }

    @Test
    void aRouteIdlingPastThirtyMinutesIsRemovedFromTheRunningContextAndRebuiltLazily() {
        reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                null, MAPPING_M3));
        RouteInstance first = factory.ensure(TENANT, reader.current(ENDPOINT_A));
        factory.touch(ENDPOINT_A, Instant.parse("2026-10-12T12:00:00Z"));

        factory.evictIdle(Instant.parse("2026-10-12T12:29:59Z"));
        assertThat(factory.instanceOf(ENDPOINT_A))
                .as("29 minutes 59 seconds idle: still inside the 30-minute window").isPresent();

        factory.evictIdle(Instant.parse("2026-10-12T12:30:01Z"));
        assertThat(factory.instanceOf(ENDPOINT_A))
                .as("30 minutes idle removes the route instance (ADR-010 section 3)")
                .isEmpty();
        assertThat(context.getRoute(first.camelRouteId())).as("the idle route is gone from the context").isNull();

        RouteInstance rebuilt = factory.ensure(TENANT, reader.current(ENDPOINT_A));
        assertThat(rebuilt.routeId()).as("lazy rebuild keeps the configuration row's route identity")
                .isEqualTo(first.routeId());
        assertThat(context.getRoute(rebuilt.camelRouteId()))
                .as("the next delivery lazily rebuilds the route on this consuming instance").isNotNull();
        assertThat(template.builds).isEqualTo(2).as("the rebuild went through the template again");
    }

    @Test
    void aFailedStopKeepsTheInstanceSoTheNextSweepRetriesAndNoOrphanRouteClashes() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        DefaultCamelContext spy = new DefaultCamelContext() {
            @Override
            public void removeRoute(org.apache.camel.Route route) {
                if (failing.get()) {
                    throw new IllegalStateException("stop failed");
                }
                super.removeRoute(route);
            }
        };
        spy.start();
        try {
            RouteInstanceFactory evicting = new RouteInstanceFactory(reader, spy, List.of(template),
                    Set.of("https"), Duration.ofMinutes(30), () -> Instant.parse("2026-10-12T12:00:00Z"));
            reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                    null, MAPPING_M3));
            RouteInstance first = evicting.ensure(TENANT, reader.current(ENDPOINT_A));
            evicting.touch(ENDPOINT_A, Instant.parse("2026-10-12T12:00:00Z"));

            evicting.evictIdle(Instant.parse("2026-10-12T12:31:00Z"));

            assertThat(evicting.instanceOf(ENDPOINT_A))
                    .as("T161 L2: the route could not be stopped, so the instance stays held (was: map entry gone,"
                            + " route orphaned, every later ensure dead-lettered PERMANENT)").isPresent();
            assertThat(evicting.ensure(TENANT, reader.current(ENDPOINT_A)).routeId()).isEqualTo(first.routeId());

            failing.set(false);
            evicting.evictIdle(Instant.parse("2026-10-12T12:32:00Z"));

            assertThat(evicting.instanceOf(ENDPOINT_A)).as("the next sweep removes it").isEmpty();
            assertThat(spy.getRoute(first.camelRouteId())).isNull();
            assertThat(evicting.ensure(TENANT, reader.current(ENDPOINT_A)).routeId())
                    .as("and the lazy rebuild works, no clash").isEqualTo(first.routeId());
        } finally {
            spy.stop();
        }
    }

    @Test
    void aRouteIdAlreadyInTheContextWithoutAHeldInstanceIsRefusedAsAClash() {
        reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                null, MAPPING_M3));
        factory.ensure(TENANT, reader.current(ENDPOINT_A));
        RouteInstanceFactory other = new RouteInstanceFactory(reader, context, List.of(template),
                Set.of("https"), Duration.ofMinutes(30), () -> Instant.parse("2026-10-12T12:00:00Z"));

        assertThatThrownBy(() -> other.ensure(TENANT, reader.current(ENDPOINT_A)))
                .isInstanceOfSatisfying(RouteBuildRefusedException.class, refused -> assertThat(refused.getMessage())
                        .as("T161 N-2: the clash is a RouteBuildRefusedException (stage 2 dead-letters it PERMANENT)")
                        .contains("already in the Camel context"));
    }

    @Test
    void publishIsIdempotentAndNeverRebuildsARouteThatIsAlreadyInstantiated() {
        reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                null, MAPPING_M3));
        factory.ensure(TENANT, reader.current(ENDPOINT_A));
        factory.publishInterface(TENANT, INTERFACE_ID);
        factory.publishInterface(TENANT, INTERFACE_ID);
        factory.ensure(TENANT, reader.current(ENDPOINT_A));

        assertThat(template.builds).isEqualTo(1)
                .as("republishing the same rows and ensuring again creates no second build (lazy re-use)");
    }

    @Test
    void anUnapprovedSchemeIsRefusedAndThePublishFailsWithAClearError() {
        reader.add(new RouteConfigRow(instanceId("route-3"), ENDPOINT_A, "API", "exec:/bin/echo", null, MAPPING_M3));

        assertThatThrownBy(() -> factory.publishInterface(TENANT, INTERFACE_ID))
                .as("T-120 machinery at route build: configuration cannot reach an unapproved component")
                .isInstanceOf(RouteBuildRefusedException.class)
                .hasMessageContaining("'exec'");
        assertThat(factory.instanceOf(ENDPOINT_A)).as("the refused route is never registered").isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource({
            "exec, exec:/bin/echo",
            "file, file:///etc/passwd",
            "http, http://unlisted.example/v1"})
    void everyUnapprovedSchemeIsRefusedAtPublishAndAtBuildNamingTheScheme(String scheme, String uri) {
        reader.add(new RouteConfigRow(instanceId("route-" + scheme), ENDPOINT_A, "API", uri, null, MAPPING_M3));
        String answer = "the publish was refused: endpoint URI scheme '" + scheme + "' is not in allowedSchemes";

        assertThatThrownBy(() -> factory.publishInterface(TENANT, INTERFACE_ID))
                .as("T71 crit 5 / T-120: publish refuses %s:", scheme)
                .isInstanceOfSatisfying(RouteBuildRefusedException.class,
                        refused -> assertThat(refused.answer()).isEqualTo(answer));
        assertThatThrownBy(() -> factory.ensure(TENANT, reader.current(ENDPOINT_A)))
                .as("T71 crit 5 / T-120: the lazy build refuses %s: too", scheme)
                .isInstanceOfSatisfying(RouteBuildRefusedException.class,
                        refused -> assertThat(refused.answer()).isEqualTo(answer));
        assertThat(factory.instanceOf(ENDPOINT_A)).as("never built, never registered").isEmpty();
    }

    @Test
    void aRefusalDetailNamesTheSchemeEndpointAndRouteButNeverTheEndpointUri() {
        UUID route = instanceId("route-5");
        reader.add(new RouteConfigRow(route, ENDPOINT_A, "API",
                "exec://user:pw@host/bin/echo?token=s3cret\r\nX-Injected: yes", null, MAPPING_M3));

        assertThatThrownBy(() -> factory.publishInterface(TENANT, INTERFACE_ID))
                .as("row 278 B22, Kavita M1: the detail reaches the send-path log")
                .isInstanceOf(RouteBuildRefusedException.class)
                .satisfies(refused -> assertThat(refused.getMessage())
                        .contains("'exec'", ENDPOINT_A.toString(), route.toString())
                        .doesNotContain("user:pw", "s3cret", "token=", "\r", "\n"));
    }

    @Test
    void aRefusalDetailNeverEchoesTextThatIsNotASchemeOrAType() {
        reader.add(new RouteConfigRow(instanceId("route-6"), ENDPOINT_A, "API", "token@host:8443/x", null, MAPPING_M3));
        assertThatThrownBy(() -> factory.publishInterface(TENANT, INTERFACE_ID))
                .isInstanceOf(RouteBuildRefusedException.class)
                .satisfies(refused -> assertThat(refused.getMessage())
                        .contains("(not a URI scheme)").doesNotContain("token@host"));

        reader.replace(instanceId("route-6"), new RouteConfigRow(instanceId("route-6"), ENDPOINT_A,
                "FTP\r\nX-Injected: yes", "https://target-a.example/v1", null, MAPPING_M3));
        assertThatThrownBy(() -> factory.publishInterface(TENANT, INTERFACE_ID))
                .isInstanceOf(RouteBuildRefusedException.class)
                .satisfies(refused -> assertThat(refused.getMessage())
                        .contains("(not a target type)").doesNotContain("X-Injected", "\r", "\n"));
    }

    @Test
    void theRefusalAnswerNamesTheSchemeOrTypeButNeverTheUri() {
        reader.add(new RouteConfigRow(instanceId("route-7"), ENDPOINT_A, "API",
                "exec://user:pw@host/bin/echo?token=s3cret", null, MAPPING_M3));
        assertThatThrownBy(() -> factory.publishInterface(TENANT, INTERFACE_ID))
                .isInstanceOfSatisfying(RouteBuildRefusedException.class, refused -> assertThat(refused.answer())
                        .as("row 278 C2: the client-safe text is answer(), routing's own")
                        .isEqualTo("the publish was refused: endpoint URI scheme 'exec' is not in allowedSchemes"));

        reader.replace(instanceId("route-7"), new RouteConfigRow(instanceId("route-7"), ENDPOINT_A, "FTP",
                "https://target-a.example/v1", null, MAPPING_M3));
        assertThatThrownBy(() -> factory.publishInterface(TENANT, INTERFACE_ID))
                .isInstanceOfSatisfying(RouteBuildRefusedException.class, refused -> assertThat(refused.answer())
                        .isEqualTo("the publish was refused: no route template for target type 'FTP'"));
    }

    @Test
    void aRouteIdAlreadyInTheContextIsRefusedWithItsOwnAnswer() throws Exception {
        RouteConfigRow row = new RouteConfigRow(instanceId("route-8"), ENDPOINT_A, "API",
                "https://target-a.example/v1", null, MAPPING_M3);
        String routeId = RouteInstanceFactory.routeIdOf(row);
        context.addRoutes(new org.apache.camel.builder.RouteBuilder() {
            @Override
            public void configure() {
                from("direct:held-elsewhere").routeId(routeId).log("x");
            }
        });

        assertThatThrownBy(() -> factory.ensure(TENANT, row))
                .isInstanceOfSatisfying(RouteBuildRefusedException.class, refused -> {
                    assertThat(refused.answer()).isEqualTo("the route instance is already in the running context");
                    assertThat(refused.getMessage()).as("the log detail names the route id").contains(routeId);
                });
    }

    @Test
    void theRefusalIsRoutingsOwnTypeWithNoHttpStatus() {
        assertThat(RouteBuildRefusedException.class.getSuperclass())
                .as("ADR-017 Decision 3: a plain RuntimeException, nothing from config")
                .isEqualTo(RuntimeException.class);
    }

    @Test
    void publishValidatesTwoValidRowsAndBuildsNothing() {
        reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                null, MAPPING_M3));
        reader.add(new RouteConfigRow(instanceId("route-2"), ENDPOINT_B, "API", "https://target-b.example/v1",
                null, MAPPING_M4));

        factory.publishInterface(TENANT, INTERFACE_ID);

        assertThat(context.getRoutes()).as("ADR-010 s3 amendment (AC-1): publish adds 0 Camel routes").isEmpty();
        assertThat(factory.instanceOf(ENDPOINT_A)).as("publish writes 0 cache entries").isEmpty();
        assertThat(factory.instanceOf(ENDPOINT_B)).isEmpty();
        assertThat(template.builds).as("publish never builds a template").isZero();
    }

    @Test
    void aPublishOfAValidThenARefusedSchemeRowFailsAndRegistersNothing() {
        reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                null, MAPPING_M3));
        reader.add(new RouteConfigRow(instanceId("route-3"), ENDPOINT_B, "API", "exec:/bin/echo", null, MAPPING_M4));

        assertThatThrownBy(() -> factory.publishInterface(TENANT, INTERFACE_ID))
                .isInstanceOf(RouteBuildRefusedException.class)
                .hasMessageContaining("'exec'");
        assertThat(context.getRoutes()).as("F14: the valid row before the refused one left no live route").isEmpty();
        assertThat(factory.instanceOf(ENDPOINT_A)).as("and no cached instance").isEmpty();
    }

    @Test
    void anUnknownTargetTypeIsRefusedAtPublishWithNoRouteRegistered() {
        reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                null, MAPPING_M3));
        reader.add(new RouteConfigRow(instanceId("route-4"), ENDPOINT_B, "FTP", "https://target-b.example/v1",
                null, MAPPING_M4));

        assertThatThrownBy(() -> factory.publishInterface(TENANT, INTERFACE_ID))
                .as("the template-exists check runs at publish")
                .isInstanceOf(RouteBuildRefusedException.class)
                .hasMessageContaining("'FTP'");
        assertThat(context.getRoutes()).as("0 routes registered by a refused publish").isEmpty();
        assertThat(factory.instanceOf(ENDPOINT_A)).isEmpty();
    }

    // row278 D3/F16: the cache key is (target endpoint id, endpoint version), ADR-010 section 3

    @Test
    void twoDeliveriesForOneEndpointVersionShareOneInstance() {
        RouteConfigRow m3 = new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API",
                "https://target-a.example/v1", null, MAPPING_M3);

        RouteInstance first = factory.ensure(TENANT, m3);
        RouteInstance second = factory.ensure(TENANT, m3);

        assertThat(second).as("the same (endpoint, version) key re-uses the instance").isSameAs(first);
        assertThat(template.builds).isEqualTo(1);
        assertThat(first.key()).isEqualTo(new RouteKey(TENANT, ENDPOINT_A, m3.endpointVersion()));
    }

    @Test
    void aNewEndpointVersionBuildsASecondInstanceAndLeavesTheFirstRunning() throws Exception {
        RouteConfigRow m3 = new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API",
                "https://target-a.example/v1", null, MAPPING_M3);
        RouteConfigRow m4 = new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API",
                "https://target-a.example/v1", null, MAPPING_M4);

        RouteInstance onM3 = factory.ensure(TENANT, m3);
        RouteInstance onM4 = factory.ensure(TENANT, m4);

        assertThat(onM4).as("a new version is a new key: its own instance").isNotSameAs(onM3);
        assertThat(template.builds).isEqualTo(2);
        assertThat(onM3.pinnedMappingVersionId()).as("the in-flight version is untouched").isEqualTo(MAPPING_M3);
        assertThat(onM4.pinnedMappingVersionId()).isEqualTo(MAPPING_M4);
        assertThat(onM3.camelRouteId()).isNotEqualTo(onM4.camelRouteId());
        assertThat(onM3.consumerUri()).isNotEqualTo(onM4.consumerUri());
        assertThat(context.getRoute(onM3.camelRouteId())).as("both versions live side by side").isNotNull();
        assertThat(context.getRoute(onM4.camelRouteId())).isNotNull();
        try (org.apache.camel.ProducerTemplate producer = context.createProducerTemplate()) {
            producer.sendBody(onM3.consumerUri(), "old");
            producer.sendBody(onM4.consumerUri(), "new");
        }
        assertThat(factory.instanceOf(ENDPOINT_A)).as("the endpoint's newest instance").contains(onM4);
    }

    @Test
    void theIdleWindowIsPerVersionAnOldVersionGoesWhileTheUsedOneStays() {
        RouteConfigRow m3 = new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API",
                "https://target-a.example/v1", null, MAPPING_M3);
        RouteConfigRow m4 = new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API",
                "https://target-a.example/v1", null, MAPPING_M4);
        RouteInstance onM3 = factory.ensure(TENANT, m3); // built 12:00, never used again
        RouteInstance onM4 = factory.ensure(TENANT, m4);
        factory.touch(onM4, Instant.parse("2026-10-12T12:29:00Z"));

        factory.evictIdle(Instant.parse("2026-10-12T12:31:00Z"));

        assertThat(context.getRoute(onM3.camelRouteId())).as("31 minutes idle: removed").isNull();
        assertThat(context.getRoute(onM4.camelRouteId())).as("used 2 minutes ago: stays").isNotNull();
        assertThat(factory.instanceOf(ENDPOINT_A)).contains(onM4);
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

    @Test
    void theTenantIsPartOfTheInstanceKeyAndAnotherTenantNeverGetsTheBuiltRoute() {
        UUID other = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
        RouteConfigRow row = new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API",
                "https://target-a.example/v1", null, MAPPING_M3);
        reader.add(row);

        RouteInstance mine = factory.ensure(TENANT, row);

        assertThat(mine.key().tenantId()).isEqualTo(TENANT);
        assertThat(row.key(TENANT)).isNotEqualTo(row.key(other)).isEqualTo(mine.key());
        assertThatThrownBy(() -> factory.ensure(other, row))
                .as("L-7: the same endpoint row under another tenant is not served the first tenant's built route")
                .isInstanceOf(RouteBuildRefusedException.class);
        assertThatThrownBy(() -> new RouteKey(null, ENDPOINT_A, "v")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRouteLookupThatFailsDoesNotAbortTheWholeEvictionSweep() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean();
        org.apache.camel.impl.DefaultCamelContext flaky = new org.apache.camel.impl.DefaultCamelContext() {
            @Override
            public org.apache.camel.Route getRoute(String id) {
                if (failing.get()) {
                    throw new IllegalStateException("lookup failed");
                }
                return super.getRoute(id);
            }
        };
        flaky.start();
        try {
            RouteInstanceFactory evicting = new RouteInstanceFactory(reader, flaky, List.of(template),
                    Set.of("https"), Duration.ofMinutes(30), () -> Instant.parse("2026-10-12T12:00:00Z"));
            reader.add(new RouteConfigRow(instanceId("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1",
                    null, MAPPING_M3));
            evicting.ensure(TENANT, reader.current(ENDPOINT_A));
            evicting.touch(ENDPOINT_A, Instant.parse("2026-10-12T12:00:00Z"));
            failing.set(true);

            assertThatCode(() -> evicting.evictIdle(Instant.parse("2026-10-12T12:31:00Z"))).doesNotThrowAnyException();
            assertThat(evicting.instanceOf(ENDPOINT_A)).as("a failed lookup keeps the instance held").isPresent();
        } finally {
            failing.set(false);
            flaky.stop();
        }
    }
}
