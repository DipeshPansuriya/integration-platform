package io.integrationplatform.e9.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The naming logic of the 1 s routing budget, shown without containers (T-122, E9-F1-S3 @negative).
 *
 * <pre>
 * &#64;negative
 * Scenario: Over budget fails the test
 *   Given a routing step that exceeds 1 s
 *   When the performance test runs
 *   Then it fails and names the slow step
 * </pre>
 *
 * <p>This runs in the plain (non-container) suite: the budget's failure names the slow step by NAME - here the
 * fake step {@link RoutingStepBudget.Step#CREATE_DELIVERIES} wired to 1.4 s - and it names ONLY steps over
 * budget, never a step inside it.
 */
class RoutingStepBudgetTest {

    // @negative Scenario: Over budget fails the test - a step over 1 s fails and names the slow step by name.
    @Test
    void aStepOverBudgetFailsAndNamesTheSlowStep() {
        RoutingStepBudget budget = new RoutingStepBudget();
        UUID messageId = UUID.fromString("52525252-0000-4000-8000-000000000052");
        budget.record(messageId, RoutingStepBudget.Step.LOAD, Duration.ofMillis(120));
        budget.record(messageId, RoutingStepBudget.Step.EVALUATE_CONDITIONS, Duration.ofMillis(310));
        budget.record(messageId, RoutingStepBudget.Step.CREATE_DELIVERIES, Duration.ofMillis(1400));

        var violations = budget.overBudget();

        assertThat(violations).as("exactly the slow step, by name").hasSize(1);
        assertThat(violations.get(0))
                .contains("CREATE_DELIVERIES")
                .contains("1400 ms")
                .contains("budget 1000 ms");

        assertThatThrownBy(budget::checkWithinBudget)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("CREATE_DELIVERIES");
    }

    // Scenario: Routing under 1 s - every recorded step inside the budget passes.
    @Test
    void stepsInsideTheBudgetPass() {
        RoutingStepBudget budget = new RoutingStepBudget();
        UUID messageId = UUID.fromString("63636363-0000-4000-8000-000000000063");
        budget.record(messageId, RoutingStepBudget.Step.LOAD, Duration.ofMillis(120));
        budget.record(messageId, RoutingStepBudget.Step.EVALUATE_CONDITIONS, Duration.ofMillis(310));
        budget.record(messageId, RoutingStepBudget.Step.CREATE_DELIVERIES, Duration.ofMillis(480));

        budget.checkWithinBudget();

        assertThat(budget.overBudget()).as("nothing over the 1 s budget").isEmpty();
    }
}