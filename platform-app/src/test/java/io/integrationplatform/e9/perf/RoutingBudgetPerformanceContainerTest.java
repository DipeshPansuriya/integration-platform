package io.integrationplatform.e9.perf;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.e9.isolation.E9TestNetwork;
import io.integrationplatform.e9.isolation.Stage1Environment;
import io.integrationplatform.e9.isolation.TargetStub;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * E9-F1-S3 (T-122, routing budget 1 s; E9-C8).
 *
 * <pre>
 * Scenario: Routing under 1 s
 *   Given 1,000 messages for an interface with three targets
 *   When they are routed
 *   Then each message's routing step (load, evaluate conditions, create deliveries) finishes under 1 s
 * </pre>
 *
 * <p>Drive: 1,000 accepted records (one interface, three ACTIVE routes) into {@code
 * platform.message.accepted}; each message's three deliveries are polled by its business key and its routing
 * step measured against {@link RoutingStepBudget#STEP_BUDGET}; the over-budget failure names the slow step
 * ({@link RoutingStepBudget}, proven by {@link RoutingStepBudgetTest}). The whole application runs
 * ({@code @SpringBootTest}, E9 reopen B1). The measurement is the whole-step upper bound
 * {@link RoutingStepBudget#recordUpperBound} - the produce-to-routed interval, an upper bound of the routing step
 * (a zero-observation run still fails).
 *
 * <p>Ordering note: the 1,000 business keys are distinct ({@code perf-key-N}), so PER_KEY holds never interfere
 * with the measured routing step; the budget is per message, not per key.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(E9TestNetwork.class)
class RoutingBudgetPerformanceContainerTest {

    private static final int MESSAGES = 1000;
    private static final int TARGETS = 3;
    private static final Duration SETTLE = Duration.ofSeconds(60);

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
        registry.add("IPASS_KAFKA_PUBLISH_CLIENT_ID", () -> "e9-perf");
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
    private static final List<TargetStub> targets = new ArrayList<>();
    private static final RoutingStepBudget budget = new RoutingStepBudget();
    private static io.micrometer.core.instrument.MeterRegistry meters;

    @BeforeEach
    void givenAnInterfaceWithThreeTargets() throws Exception {
        if (env != null) {
            return;
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        env = new Stage1Environment(POSTGRES, login, KAFKA.getBootstrapServers());
        for (int t = 0; t < TARGETS; t++) {
            TargetStub stub = TargetStub.answering("/perf-target-" + t, 200);
            targets.add(stub);
            env.registerRoute("perf-target-" + t, stub.baseUrl());
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    void captureDrainMeters(io.micrometer.core.instrument.MeterRegistry registry) {
        meters = registry;
    }

    @AfterAll
    static void stopTheTargetStubs() {
        for (TargetStub stub : targets) {
            stub.stop();
        }
    }

    @Test
    void eachRoutingStepOf1000MessagesFinishesUnderOneSecond() {
        // Given 1,000 messages for the interface with its three targets - When: they are routed
        UUID[] messageIds = new UUID[MESSAGES];
        Instant[] producedAt = new Instant[MESSAGES];
        var producer = env.acceptedProducer();
        for (int i = 0; i < MESSAGES; i++) {
            messageIds[i] = UUID.randomUUID();
            producedAt[i] = Instant.now();
            env.produceAccepted(producer, messageIds[i], businessKey(i), "{\"seq\":" + i + "}");
        }

        // Then: each message's routing step (load, evaluate conditions, create deliveries) within 1 s
        for (int i = 0; i < MESSAGES; i++) {
            int index = i;
            UUID messageId = messageIds[index];
            Stage1Environment.pollUntil(SETTLE,
                    () -> deliveriesCreatedFor(index),
                    "the three deliveries of business key " + businessKey(index)
                            + " (routing stage 1 T-117/T-178 did not create them)");
            budget.recordUpperBound(messageId, Duration.between(producedAt[index], Instant.now()));
        }

        // T-122 AC 3: the drain's per-step probes, named the way the budget names the hoard.
        reportDrainAttribution();
        budget.checkWithinBudget();
    }

    /** Present when all 3 deliveries exist; absent keeps the poll alive. */
    private Optional<Boolean> deliveriesCreatedFor(int index) {
        return env.queryOneAsApp("select count(*) as count from public.delivery"
                + " where tenant_id = ? and interface_id = ? and business_key = ?",
                Stage1Environment.TENANT, Stage1Environment.INTERFACE_ID, businessKey(index))
                .map(row -> ((Number) row.get("count")).longValue())
                .filter(count -> count >= TARGETS)
                .map(count -> true);
    }

    private static String businessKey(int index) {
        return "perf-key-" + index;
    }

    /** T-122 AC 3: probes wired inside the drain step (StageConsumer.drainOnce timers) report per sub-step:
     * "backlog drain" (poll wait), "routing drain" (per-record stage-1 handling, offset commit), "poll settle"
     * (the empty-turn waits) - the attribution the whole-step upper bound alone could not give. */
    private static void reportDrainAttribution() {
        if (meters == null) {
            throw new AssertionError("the drain probes were never read: the application's MeterRegistry is not"
                    + " autowired into this test, so AC-3 attribution cannot be proven");
        }
        meters.find("platform.stage.drain").timers().forEach(timer -> {
            String step = timer.getId().getTag("step");
            if (step != null) {
                String line = "drain step " + step + " (" + drainStepName(step) + ") group="
                        + timer.getId().getTag("group") + " count=" + timer.count()
                        + " totalMs=" + timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)
                        + " maxMs=" + timer.max(java.util.concurrent.TimeUnit.MILLISECONDS);
                System.out.println(line);
                budget.addDrainAttribution(line);
            }
        });
    }

    private static String drainStepName(String step) {
        return switch (step) {
            case "poll" -> "backlog drain: poll wait";
            case "handle" -> "routing drain: per-record stage-1 handling";
            case "commit" -> "routing drain: offset commit";
            case "settle" -> "poll settle: empty-turn wait";
            default -> step;
        };
    }

    /** The naming logic alone is proven by RoutingStepBudgetTest; this documents the same contract here. */
    @Test
    void theZeroObservationRunNeverPassesTheBudgetAssert() {
        assertThatThrownBy(new RoutingStepBudget()::checkWithinBudget)
                .as("no observation measured - the assert must fail, never pass")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no routing step was measured");
    }
}