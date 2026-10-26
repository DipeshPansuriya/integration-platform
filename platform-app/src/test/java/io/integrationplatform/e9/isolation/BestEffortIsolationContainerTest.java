package io.integrationplatform.e9.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E9-F1-S2 (T-118, best-effort isolation across targets; E9-C4).
 *
 * <pre>
 * Scenario: Best-effort across targets
 *   Given Target A (declares idempotent receive) answering 500 and Target B answering 200
 *   When one message is routed to both
 *   Then Target B's delivery is DELIVERED and Target A's goes to RETRY_WAIT; nothing is rolled back
 * </pre>
 *
 * <p>Drive: one accepted record into {@code platform.message.accepted} (the Kafka harness, unchanged); routing
 * stage 1 (T-117/T-178, lane C) fans it out and pins one delivery per route; stage 2 (the target adapter, lane
 * A1) sends - Target B's 200 confirms the delivery DELIVERED, Target A's 500 leaves it in RETRY_WAIT for the
 * retry layer (A declares idempotent receive, so a 500 is TRANSIENT; without it a 500 is AMBIGUOUS and
 * dead-letters, ADR-010 outcome table); best-effort means both rows persist and the settled states hold. The whole application runs
 * ({@code @SpringBootTest}, E9 reopen B1); a missing link leaves no delivery and the poll's failure names it.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(E9TestNetwork.class)
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class BestEffortIsolationContainerTest {

    private static final Duration SETTLE = Duration.ofSeconds(60);
    private static final UUID MESSAGE_ID = UUID.fromString("42424242-0000-4000-8000-000000000042");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(TestImages.KAFKA)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    private static RestrictedLogin login;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KAFKA.start();
        E9TestNetwork.createTheThreeTopics(KAFKA.getBootstrapServers());
        registry.add("IPASS_KAFKA_BOOTSTRAP_SERVERS", KAFKA::getBootstrapServers);
        registry.add("IPASS_KAFKA_PUBLISH_CLIENT_ID", () -> "e9-isolation");
        // test config only: the stub host is allowed here, the destination guard itself is unchanged
        registry.add("platform.config.destinations.allowed-hosts", () -> E9TestNetwork.ALLOWED_HOSTS);
        registry.add("ROUTING_CORE_ALLOWED_SCHEMES", () -> "http,https");
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class GrantAppRoleWiring {
        @org.springframework.context.annotation.Bean
        org.flywaydb.core.api.callback.Callback e9GrantAppRole() {
            return E9TestNetwork.grantAppRoleAfterMigrate(POSTGRES, () -> login);
        }
    }

    private static Stage1Environment env;
    private static TargetStub targetA;
    private static TargetStub targetB;
    private static Stage1Environment.RouteRows routeToA;
    private static Stage1Environment.RouteRows routeToB;

    // Given Target A (idempotent receive, so its 500 is TRANSIENT) answering 500 and Target B answering 200.
    @BeforeEach
    void givenTheTwoTargetsAndTheirRoutes() throws Exception {
        if (env != null) {
            return;
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        env = new Stage1Environment(POSTGRES, login, KAFKA.getBootstrapServers());
        targetA = TargetStub.answering("/target-a-500", 500);
        targetB = TargetStub.answering("/target-b-200", 200);
        routeToA = env.registerRoute("e9-target-a", targetA.baseUrl(), true);
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
        // per target name: the endpoint ids are random, so an order by target_id is not an A-then-B order
        assertThat(statesOf(routeToA.endpointId())).as("Target A's delivery persists in RETRY_WAIT")
                .containsExactly("RETRY_WAIT");
        assertThat(statesOf(routeToB.endpointId())).as("Target B's delivery persists DELIVERED (not rolled back)")
                .containsExactly("DELIVERED");
    }

    // Stage 2 actually addressed both stubs.
    @Test
    @org.junit.jupiter.api.Order(3)
    void bothTargetsWereAddressed() {
        assertThat(targetB.calls()).as("Target B answered 200").isPositive();
        assertThat(targetA.calls()).as("Target A answered 500").isPositive();
    }

    private List<String> statesOf(UUID targetEndpointId) {
        return env.queryListAsApp("select state from public.delivery"
                + " where tenant_id = ? and interface_id = ? and target_id = ?", String.class,
                Stage1Environment.TENANT, Stage1Environment.INTERFACE_ID, targetEndpointId);
    }

    /** The one delivery of one target; polls the full settle deadline. */
    private Map<String, Object> oneDeliveryOfTarget(UUID targetEndpointId, String named) {
        return Stage1Environment.pollUntil(SETTLE,
                () -> env.queryOneAsApp("select id, state, next_attempt_at from public.delivery"
                        + " where tenant_id = ? and interface_id = ? and target_id = ?"
                        + " and state not in ('PENDING', 'SENDING') limit 1", // settled (ADR-010:275)
                        Stage1Environment.TENANT, Stage1Environment.INTERFACE_ID, targetEndpointId),
                "a delivery of " + named + " (routing stage 1 T-117/T-178 or stage 2 T-177 did not act)");
    }
}