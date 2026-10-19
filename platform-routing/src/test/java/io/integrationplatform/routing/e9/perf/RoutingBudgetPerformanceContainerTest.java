package io.integrationplatform.routing.e9.perf;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.routing.e9.isolation.Stage1Environment;
import io.integrationplatform.routing.e9.isolation.TargetStub;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E9-F1-S3 (T-122, routing budget 1 s; E9-C8; lane C makes this green - RED until then).
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
 * ({@link RoutingStepBudget}, proven by {@link RoutingStepBudgetTest}). Wave-0 RED: routing stage 1
 * (T-117/T-178) does not exist on the branch tip - no record is consumed, the first settle poll expires and the
 * test fails naming stage 1 as absent; it must never pass as a false green. When lane C lands, the per-step
 * boundaries record through {@link RoutingStepBudget#record}; until then the measurement is the whole-step upper
 * bound {@link RoutingStepBudget#recordUpperBound} - the produce-to-routed interval, an upper bound of the
 * routing step (documented, and a zero-observation run still fails).
 *
 * <p>Ordering note: the 1,000 business keys are distinct ({@code perf-key-N}), so PER_KEY holds never interfere
 * with the measured routing step; the budget is per message, not per key.
 */
@Tag("container")
class RoutingBudgetPerformanceContainerTest {

    private static final int MESSAGES = 1000;
    private static final int TARGETS = 3;
    private static final Duration SETTLE = Duration.ofSeconds(60);

    private static Stage1Environment env;
    private static final List<TargetStub> targets = new ArrayList<>();
    private static final RoutingStepBudget budget = new RoutingStepBudget();

    @BeforeAll
    static void givenAnInterfaceWithThreeTargets() throws Exception {
        env = Stage1Environment.get();
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

        // Then: each message's routing step (load, evaluate conditions, create deliveries) within 1 s
        for (int i = 0; i < MESSAGES; i++) {
            int index = i;
            UUID messageId = messageIds[index];
            Stage1Environment.pollUntil(SETTLE,
                    () -> deliveriesCreatedFor(index),
                    "the three deliveries of business key " + businessKey(index)
                            + " (routing stage 1 T-117/T-178 is not on the branch tip yet - lane C makes this"
                            + " green; the whole run stays red until then)");
            budget.recordUpperBound(messageId, Duration.between(producedAt[index], Instant.now()));
        }

        budget.checkWithinBudget();
    }

    /** Present when all 3 deliveries exist; absent keeps the poll alive - the wave-0 red lives here. */
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

    /** The naming logic alone is proven by RoutingStepBudgetTest; this documents the same contract here. */

    /** The naming logic alone is proven by RoutingStepBudgetTest; this documents the same contract here. */
    @Test
    void theZeroObservationRunNeverPassesTheBudgetAssert() {
        assertThatThrownBy(new RoutingStepBudget()::checkWithinBudget)
                .as("no observation measured - the assert must fail, never pass")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no routing step was measured");
    }
}