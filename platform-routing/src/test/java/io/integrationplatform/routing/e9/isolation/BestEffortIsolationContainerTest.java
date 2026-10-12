package io.integrationplatform.routing.e9.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * E9-F1-S2 (T-118, best-effort isolation across targets; E9-C4; lanes A1/C make this green - RED until then).
 *
 * <pre>
 * Scenario: Best-effort across targets
 *   Given Target A answering 500 and Target B answering 200
 *   When one message is routed to both
 *   Then Target B's delivery is DELIVERED and Target A's goes to RETRY_WAIT; nothing is rolled back
 * </pre>
 *
 * <p>Drive: one accepted record into {@code platform.message.accepted} (the Kafka harness, unchanged); routing
 * stage 1 (T-117/T-178, lane C) fans it out and pins one delivery per route; stage 2 (the target adapter, lane
 * A1) sends - Target B's 200 confirms the delivery DELIVERED, Target A's 500 leaves it in RETRY_WAIT for the
 * retry layer; best-effort means both rows persist and the settled states hold. Wave-0 RED: with no stage-1 or
 * stage-2 on the branch tip, no delivery is ever created and the poll expires - the failure names exactly what is
 * missing; it must never pass as a false green.
 */
@Tag("container")
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class BestEffortIsolationContainerTest {

    private static final Duration SETTLE = Duration.ofSeconds(60);
    private static final UUID MESSAGE_ID = UUID.fromString("42424242-0000-4000-8000-000000000042");

    private static Stage1Environment env;
    private static TargetStub targetA;
    private static TargetStub targetB;
    private static Stage1Environment.RouteRows routeToA;
    private static Stage1Environment.RouteRows routeToB;

    // Given Target A answering 500 and Target B answering 200 (and their routes registered).
    @BeforeAll
    static void givenTheTwoTargetsAndTheirRoutes() throws Exception {
        env = Stage1Environment.get();
        targetA = TargetStub.answering("/target-a-500", 500);
        targetB = TargetStub.answering("/target-b-200", 200);
        routeToA = env.registerRoute("e9-target-a", targetA.baseUrl());
        routeToB = env.registerRoute("e9-target-b", targetB.baseUrl());
    }

    @AfterAll
    static void stopTheTargets() {
        if (targetA != null) {
            targetA.stop();
        }
        if (targetB != null) {
            targetB.stop();
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void targetBIsDeliveredAndTargetAWaitsInRetryWait() {
        // When: one message is routed to both
        env.produceAccepted(MESSAGE_ID, "iso-key-1", "{\"amount\":1250}");

        // Then: Target B's delivery is DELIVERED ...
        Map<String, Object> delivered = oneDeliveryOfTarget(routeToB.endpointId(), "Target B");
        assertThat(delivered).as("Target B's delivery").isNotNull();
        assertThat(delivered.get("state")).as("Target B's delivery state").isEqualTo("DELIVERED");

        // ... and Target A's goes to RETRY_WAIT
        Map<String, Object> retrying = oneDeliveryOfTarget(routeToA.endpointId(), "Target A");
        assertThat(retrying).as("Target A's delivery").isNotNull();
        assertThat(retrying.get("state")).as("Target A's delivery state").isEqualTo("RETRY_WAIT");
    }

    // ... and nothing is rolled back: after the settle, both rows hold the same final-for-now states
    // (Target B DELIVERED is not rolled back when Target A retries).
    @Test
    @org.junit.jupiter.api.Order(2)
    void nothingWasRolledBack() {
        List<String> states = env.queryListAsApp("select state from public.delivery"
                + " where tenant_id = ? and interface_id = ? and target_id in (?, ?) order by target_id",
                String.class, Stage1Environment.TENANT, Stage1Environment.INTERFACE_ID,
                routeToA.endpointId(), routeToB.endpointId());

        assertThat(states).as("both deliveries persist with their states (nothing rolled back)")
                .containsExactly("RETRY_WAIT", "DELIVERED");
    }

    // Green-when-landed: once stage 2 sends, both stubs were actually addressed (red now: nobody sends yet).
    @Test
    @org.junit.jupiter.api.Order(3)
    void bothTargetsWereAddressed() {
        assertThat(targetB.calls()).as("Target B answered 200").isPositive();
        assertThat(targetA.calls()).as("Target A answered 500").isPositive();
    }

    /** The one delivery of one target; polls the full settle deadline - the wave-0 red lives here. */
    private Map<String, Object> oneDeliveryOfTarget(UUID targetEndpointId, String named) {
        return Stage1Environment.pollUntil(SETTLE,
                () -> env.queryOneAsApp("select id, state, next_attempt_at from public.delivery"
                        + " where tenant_id = ? and interface_id = ? and target_id = ? limit 1",
                        Stage1Environment.TENANT, Stage1Environment.INTERFACE_ID, targetEndpointId),
                "a delivery of " + named + " (routing stage 1 T-117/T-178 and stage 2 T-177 are not on the"
                        + " branch tip yet - lane C/lane A1 make this green)");
    }
}