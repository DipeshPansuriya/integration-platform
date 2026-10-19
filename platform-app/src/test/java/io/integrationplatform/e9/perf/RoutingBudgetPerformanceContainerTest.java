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
import java.util.Map;
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
 * <p>Over-budget failures must name the hoarded step: wall-clock milestones (produce done, first routed seen,
 * all routed seen) plus a DB-side read of {@code public.delivery} {@code min}/{@code max} {@code created_at}
 * (same host clock, app-side routing truth) split the stall into backlog drain, routing drain spread, or poll
 * settle lag. The DB probe is secondary evidence and may be absent without masking the budget failure.
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
        Instant produceDone = Instant.now();

        // Then: each message's routing step (load, evaluate conditions, create deliveries) within 1 s
        Instant firstRoutedAt = null; // polls are sequential, so message 0 is the first routed observation
        for (int i = 0; i < MESSAGES; i++) {
            int index = i;
            UUID messageId = messageIds[index];
            Stage1Environment.pollUntil(SETTLE,
                    () -> deliveriesCreatedFor(index),
                    "the three deliveries of business key " + businessKey(index)
                            + " (routing stage 1 T-117/T-178 did not create them)");
            if (index == 0) {
                firstRoutedAt = Instant.now();
            }
            budget.recordUpperBound(messageId, Duration.between(producedAt[index], Instant.now()));
        }
        Instant allRoutedAt = Instant.now();

        // DB truth (app-side routing writes) separates routing lag from this poll's settle lag; secondary
        // evidence, so absence never masks the budget assert below.
        Instant firstWrite = null;
        Instant lastWrite = null;
        try {
            Map<String, Object> writes = env.queryOneAsApp(
                    "select min(created_at) as first_write, max(created_at) as last_write from public.delivery"
                            + " where tenant_id = ? and interface_id = ?",
                    Stage1Environment.TENANT, Stage1Environment.INTERFACE_ID)
                    .orElseGet(Map::of);
            firstWrite = persistedInstant(writes.get("first_write"));
            lastWrite = persistedInstant(writes.get("last_write"));
        } catch (RuntimeException probeUnavailable) {
            System.out.println("[perf-probe] db-write probe unavailable: " + probeUnavailable);
        }

        try {
            budget.checkWithinBudget();
        } catch (AssertionError overBudget) {
            String report = hoardedStepReport(produceDone, firstWrite, lastWrite, firstRoutedAt, allRoutedAt);
            System.out.println(report);
            throw new AssertionError(report + overBudget.getMessage(), overBudget);
        }
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

    /** Null-safe instant from the DB probe row; an unmapped JDBC type throws inside the caught RuntimeException and just drops the probe. */
    private static Instant persistedInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        return ((java.time.LocalDateTime) value).atZone(java.time.ZoneOffset.UTC).toInstant();
    }

    /** Long delta in ms, or null when a probe milestone is absent. */
    private static Long deltaMillis(Instant from, Instant to) {
        return from == null || to == null ? null : Duration.between(from, to).toMillis();
    }

    /** Names the hoarded step from the probe deltas - backlog drain (stage-1 writes start late), routing drain (writes spread), or poll settle lag; nulls mean the probe could not isolate it. */
    private static String hoardedStepReport(Instant produceDone, Instant firstWrite, Instant lastWrite,
            Instant firstRoutedAt, Instant allRoutedAt) {
        Long backlogDrain = deltaMillis(produceDone, firstWrite);
        Long writeSpread = deltaMillis(firstWrite, lastWrite);
        Long settleLag = deltaMillis(lastWrite, allRoutedAt);
        String hoarded;
        if (writeSpread != null && (backlogDrain == null || writeSpread >= backlogDrain)
                && (settleLag == null || writeSpread >= settleLag)) {
            hoarded = "routing drain - stage-1 writes spread over " + writeSpread + " ms";
        } else if (backlogDrain != null && (settleLag == null || backlogDrain >= settleLag)) {
            hoarded = "backlog drain - first stage-1 write lands only " + backlogDrain + " ms after produce-done";
        } else if (settleLag != null) {
            hoarded = "poll settle lag - last delivery write visible " + settleLag + " ms before all-routed";
        } else {
            hoarded = "not isolated - db-write probe unavailable, see wall-clock deltas below";
        }
        return "[perf-probe] step deltas from produce-done (ms): backlog drain to first stage-1 write=" + backlogDrain
                + ", stage-1 write spread=" + writeSpread
                + ", poll settle lag after last write=" + settleLag
                + ", produce to first routed assert=" + deltaMillis(produceDone, firstRoutedAt)
                + " | hoarded step: " + hoarded;
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