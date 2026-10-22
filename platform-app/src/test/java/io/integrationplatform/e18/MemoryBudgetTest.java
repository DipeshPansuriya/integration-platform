package io.integrationplatform.e18;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** E18-F1-S3 without Docker: the budget file loads, a run within budget passes, a run over it fails and says the peak. */
@Tag("e2e-full")
@ExtendWith(E18Outcomes.class)
class MemoryBudgetTest {

    private final MemoryBudget budget = MemoryBudget.load();

    @Test
    void theRecordedBudgetLoadsFromTheTestConfiguration() {
        assertThat(budget.heapPeakMiB()).isPositive().isLessThan(1024); // below the fork's -Xmx1g
        assertThat(budget.drainSeconds()).isPositive();
    }

    @Test
    void aRunWithinBudgetPasses() {
        budget.enforce(new MemoryBudget.Measurement(budget.heapPeakMiB(), 10, 0, 5, 10, 1, 5));
    }

    @Test
    void aRunOverBudgetFailsAndReportsThePeak() {
        long over = budget.heapPeakMiB() + 1;
        assertThatThrownBy(() -> budget.enforce(new MemoryBudget.Measurement(over, 10, 0, 5, 10, 1, 5)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("OVER BUDGET")
                .hasMessageContaining("peak heap " + over + " MiB");
    }

    @Test
    void everyBreachIsNamedNotJustTheFirst() {
        MemoryBudget.Measurement bad = new MemoryBudget.Measurement(budget.heapPeakMiB() + 1,
                budget.heapRetainedMiB() + 1, budget.poolPendingMax() + 1, 11, 10, budget.backlogMax() + 1,
                budget.drainSeconds() + 1);
        assertThat(budget.breaches(bad)).hasSize(6);
    }
}
