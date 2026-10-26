package io.integrationplatform.e9.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
 * T71 (E9-F1-S4 backlog scenario, E9-C9; the proof Varun found missing: the isolation fakes use 1-permit pools).
 *
 * <pre>
 * Scenario: A hanging target does not hold up a healthy one on the same partition
 *   Given Target A hanging (accepts the call, never answers) and Target B answering in 100 ms
 *   And 200 deliveries for each, every one on the one partition of platform.delivery.due
 *   When they are dispatched
 *   Then all 200 of Target B's deliveries are DELIVERED within BOUND (B waits behind no A delivery)
 *   And Target A's overflow is deferred (RETRY_WAIT, bulkhead_deferrals, BULKHEAD_FULL events), never blocked
 * </pre>
 *
 * <p>Drive: 200 accepted records (distinct business keys, so PER_KEY holds never interfere - a hold is per target
 * anyway) each routed to both targets, so stage 1 makes 200 A deliveries and 200 B deliveries. All three topics
 * have ONE partition, so every {@code platform.delivery.due} record is handled by the same partition thread, A's
 * and B's interleaved. The whole application runs ({@code @SpringBootTest}).
 *
 * <p>Pool size: {@value #MAX_CONCURRENT_SENDS} (the validated maximum of {@code max_concurrent_sends}). With the
 * team default of 4, Target B would also defer on its OWN full pool whenever the partition thread outpaces its
 * 100 ms sends, and the bound would measure B's retry backoff (1 s doubling) rather than isolation from A. At 32
 * B's 200 sends need about 0.6 s of pool time, so a miss of {@link #BOUND} can only be A holding B up. Target
 * A's calls hang until the stub is stopped (endpoint timeout 300 s), so A's pool stays full for the whole run.
 *
 * <p>Falsifiable: if the send ran on the partition thread instead of the target's pool, A's first hanging call
 * would stop every later record - B included - and the first test fails on its deadline (proven by a scratch
 * change, see the hand-back).
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(E9TestNetwork.class)
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class HangingTargetIsolationContainerTest {

    static final int MAX_CONCURRENT_SENDS = 32;
    private static final int DELIVERIES_PER_TARGET = 200;
    /**
     * The stated bound: from the last accepted record produced to the 200th Target B delivery DELIVERED. B's own
     * work is about 0.6 s of pool time at 32 permits; 20 s leaves 30x for routing and CI noise and is far inside
     * Target A's 300 s hang, so a miss means B waited behind A.
     */
    private static final Duration BOUND = Duration.ofSeconds(20);

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
        E9TestNetwork.createTheThreeTopics(KAFKA.getBootstrapServers(), 1); // one partition: A and B share it
        registry.add("IPASS_KAFKA_BOOTSTRAP_SERVERS", KAFKA::getBootstrapServers);
        registry.add("IPASS_KAFKA_PUBLISH_CLIENT_ID", () -> "e9-hanging-target");
        registry.add("platform.config.destinations.allowed-hosts", () -> E9TestNetwork.ALLOWED_HOSTS);
        registry.add("ROUTING_CORE_ALLOWED_SCHEMES", () -> "http,https");
        registry.add("routing.core.stage2.bulkhead.max-concurrent-sends", () -> MAX_CONCURRENT_SENDS);
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
    private static Instant allProducedAt;

    // Given Target A hanging and Target B answering in 100 ms.
    @BeforeEach
    void givenAHangingTargetAndAHundredMillisecondTarget() throws Exception {
        if (env != null) {
            return;
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        env = new Stage1Environment(POSTGRES, login, KAFKA.getBootstrapServers());
        targetA = TargetStub.hanging("/t71-target-a-hangs");
        targetB = TargetStub.answeringAfter("/t71-target-b-100ms", 200, 100);
        routeToA = env.registerRoute("t71-target-a", targetA.baseUrl(), true, 300_000);
        routeToB = env.registerRoute("t71-target-b", targetB.baseUrl());
    }

    @AfterAll
    static void releaseAndStopTheTargets() {
        if (targetA != null) {
            targetA.stop(); // releases the hung calls
        }
        if (targetB != null) {
            targetB.stop();
        }
    }

    // When 200 messages are routed to both: Then all 200 of B's deliveries are DELIVERED within the bound.
    @Test
    @org.junit.jupiter.api.Order(1)
    void allTargetBDeliveriesAreDeliveredWithinTheBoundWhileTargetAHangs() {
        var producer = env.acceptedProducer();
        for (int i = 0; i < DELIVERIES_PER_TARGET; i++) {
            env.produceAccepted(producer, UUID.randomUUID(), "t71-key-" + i, "{\"seq\":" + i + "}");
        }
        allProducedAt = Instant.now();

        Instant deadline = allProducedAt.plus(BOUND);
        long delivered = 0;
        while (Instant.now().isBefore(deadline) && (delivered = count(routeToB, "state = 'DELIVERED'"))
                < DELIVERIES_PER_TARGET) {
            sleep(250);
        }

        assertThat(delivered)
                .as("Target B deliveries DELIVERED within " + BOUND + " of the last record produced, while Target A"
                        + " hangs and shares the partition. B states: " + statesOf(routeToB)
                        + "; A states: " + statesOf(routeToA) + "; B calls: " + targetB.calls()
                        + "; A calls in flight: " + targetA.inFlight())
                .isEqualTo(DELIVERIES_PER_TARGET);
    }

    // ... and no B delivery waited behind A: B finished while every one of A's permits was still held by a hung call.
    @Test
    @org.junit.jupiter.api.Order(2)
    void targetBFinishedWhileTargetAWasStillHanging() {
        assertThat(count(routeToA, "state = 'DELIVERED'")).as("Target A never answers: none of its deliveries is DELIVERED")
                .isZero();
        assertThat(targetA.inFlight()).as("Target A's calls are still hung while B is already done").isPositive();
        assertThat(targetB.calls()).as("Target B was called once per delivery, never retried behind A")
                .isEqualTo(DELIVERIES_PER_TARGET);
    }

    // And A's overflow is deferred with bulkhead_deferrals, never blocking: its pool holds exactly the bound,
    // the other 168 wait in RETRY_WAIT with a BULKHEAD_FULL event each.
    @Test
    @org.junit.jupiter.api.Order(3)
    void targetAOverflowIsDeferredAndItsPoolHoldsExactlyTheBound() {
        int overflow = DELIVERIES_PER_TARGET - MAX_CONCURRENT_SENDS;
        Map<String, Object> a = Stage1Environment.pollUntil(BOUND, () -> {
            Map<String, Object> row = summaryOf(routeToA);
            return Optional.of(row).filter(r -> number(r, "sending") == MAX_CONCURRENT_SENDS
                    && number(r, "deferred") == overflow);
        }, "Target A to hold " + MAX_CONCURRENT_SENDS + " hung sends and " + overflow + " deferred deliveries; now "
                + summaryOf(routeToA));

        assertThat(number(a, "total")).as("Target A deliveries").isEqualTo(DELIVERIES_PER_TARGET);
        assertThat(number(a, "dead")).as("Target A dead-lettered (its timeout is 300 s, none hit)").isZero();
        assertThat(targetA.calls()).as("Target A saw only its pool's worth of calls, the rest never left the bulkhead")
                .isEqualTo(MAX_CONCURRENT_SENDS);
        long events = env.queryOneAsApp("select count(distinct e.delivery_id) as n from public.delivery_event e"
                + " join public.delivery d on d.id = e.delivery_id and d.tenant_id = e.tenant_id"
                + " where d.tenant_id = ? and d.interface_id = ? and d.target_id = ? and e.type = 'BULKHEAD_FULL'",
                Stage1Environment.TENANT, Stage1Environment.INTERFACE_ID, routeToA.endpointId())
                .map(row -> ((Number) row.get("n")).longValue()).orElse(0L);
        assertThat(events).as("Target A deliveries with a BULKHEAD_FULL deferral event").isEqualTo(overflow);
    }

    private long count(Stage1Environment.RouteRows route, String condition) {
        return env.queryOneAsApp("select count(*) as n from public.delivery where tenant_id = ? and interface_id = ?"
                + " and target_id = ? and " + condition,
                Stage1Environment.TENANT, Stage1Environment.INTERFACE_ID, route.endpointId())
                .map(row -> ((Number) row.get("n")).longValue()).orElse(0L);
    }

    private Map<String, Object> summaryOf(Stage1Environment.RouteRows route) {
        return env.queryOneAsApp("select count(*) as total,"
                + " count(*) filter (where state = 'SENDING') as sending,"
                + " count(*) filter (where state = 'RETRY_WAIT' and bulkhead_deferrals >= 1) as deferred,"
                + " count(*) filter (where state = 'DEAD_LETTERED') as dead"
                + " from public.delivery where tenant_id = ? and interface_id = ? and target_id = ?",
                Stage1Environment.TENANT, Stage1Environment.INTERFACE_ID, route.endpointId()).orElseThrow();
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private java.util.List<String> statesOf(Stage1Environment.RouteRows route) {
        return env.queryListAsApp("select state || '=' || count(*) from public.delivery where tenant_id = ?"
                + " and interface_id = ? and target_id = ? group by state order by state", String.class,
                Stage1Environment.TENANT, Stage1Environment.INTERFACE_ID, route.endpointId());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
