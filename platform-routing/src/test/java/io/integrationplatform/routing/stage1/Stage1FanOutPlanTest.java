package io.integrationplatform.routing.stage1;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.flow.application.FlowBundle;
import io.integrationplatform.delivery.port.Stage1Delivery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-049 stage 1 (ADR-010 section 2): the deliveries come from the routes of the interface version PINNED on the
 * message - one per ACTIVE route, each pinning its route's mapping version, created at the message's accepted_at
 * (ADR-014) - and a paused or conditional route is never delivered unconditionally.
 */
class Stage1FanOutPlanTest {

    private static final UUID MESSAGE = UUID.fromString("42424242-0000-4000-8000-000000000001");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-16T10:00:00Z");
    private static final String ROUTE_A = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String ROUTE_B = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final String ROUTE_PAUSED = "cccccccc-0000-4000-8000-000000000003";
    private static final String ROUTE_CONDITIONAL = "dddddddd-0000-4000-8000-000000000004";
    private static final String ENDPOINT_A = "aaaaaaaa-1111-4000-8000-000000000001";
    private static final String ENDPOINT_B = "bbbbbbbb-1111-4000-8000-000000000002";
    private static final String VERSION_A = "aaaaaaaa-2222-4000-8000-000000000001";
    private static final String VERSION_B = "bbbbbbbb-2222-4000-8000-000000000002";

    private final JdbcStage1Reads.PinnedMessage message =
            new JdbcStage1Reads.PinnedMessage(MESSAGE, "ACCEPTED", ACCEPTED_AT, UUID.randomUUID(), INTERFACE, "K-1", 3L);
    private static final UUID INTERFACE = UUID.fromString("eeeeeeee-0000-4000-8000-000000000001");

    @Test
    @DisplayName("one delivery per ACTIVE unconditional route, each pinning its own mapping version")
    void oneDeliveryPerActiveRoute() {
        FlowBundle.Parsed snapshot = new FlowBundle.Parsed(
                List.of(route(ROUTE_A, 1, null, "ACTIVE", ENDPOINT_A), route(ROUTE_B, 2, null, "ACTIVE", ENDPOINT_B),
                        route(ROUTE_PAUSED, 3, null, "PAUSED", ENDPOINT_A),
                        route(ROUTE_CONDITIONAL, 4, "amount > 100", "ACTIVE", ENDPOINT_B)),
                List.of(mapping(ROUTE_A, VERSION_A), mapping(ROUTE_B, VERSION_B)));

        List<Stage1Delivery> planned = Stage1FanOut.plan(message, snapshot);

        assertThat(planned).containsExactly(
                new Stage1Delivery(MESSAGE, UUID.fromString(ROUTE_A), ACCEPTED_AT, UUID.fromString(ENDPOINT_A),
                        UUID.fromString(VERSION_A), null, INTERFACE, "K-1", 3L),
                new Stage1Delivery(MESSAGE, UUID.fromString(ROUTE_B), ACCEPTED_AT, UUID.fromString(ENDPOINT_B),
                        UUID.fromString(VERSION_B), null, INTERFACE, "K-1", 3L));
    }

    @Test
    @DisplayName("a route whose mapping is unbound is still planned, with no mapping version (the send refuses it)")
    void unboundMappingPlannedWithoutVersion() {
        FlowBundle.Parsed snapshot = new FlowBundle.Parsed(List.of(route(ROUTE_A, 1, null, "ACTIVE", ENDPOINT_A)),
                List.of(new FlowBundle.SnapshotMapping("m-a", ROUTE_A, "[]", "$", null, null, null)));

        assertThat(Stage1FanOut.plan(message, snapshot)).singleElement()
                .satisfies(delivery -> assertThat(delivery.mappingVersionId()).isNull());
    }

    @Test
    @DisplayName("no route -> no delivery (the message still routes: 0 deliveries shows 'no route')")
    void noRouteNoDelivery() {
        assertThat(Stage1FanOut.plan(message, new FlowBundle.Parsed(List.of(), List.of()))).isEmpty();
    }

    private static FlowBundle.SnapshotRoute route(String id, int sequence, String condition, String status,
            String endpoint) {
        return new FlowBundle.SnapshotRoute(id, sequence, condition, false, status, endpoint);
    }

    private static FlowBundle.SnapshotMapping mapping(String routeId, String versionId) {
        return new FlowBundle.SnapshotMapping("m-" + routeId, routeId, "[]", "$", "src", "tgt", versionId);
    }
}
