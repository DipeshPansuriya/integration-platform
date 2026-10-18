package io.integrationplatform.e2e.release1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.e2e.release1.SuiteVerdict.Outcome;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Row 323 T139 (E15-F1-S1), no Docker: the Release 1 verdict over the nine scenarios. GREEN only when all nine
 * PASSED; any red, gated (disabled), aborted or missing scenario makes the suite RED and Release 1 may not close.
 */
class SuiteVerdictTest {

    private static Map<Release1Scenario, Outcome> allPassed() {
        Map<Release1Scenario, Outcome> outcomes = new EnumMap<>(Release1Scenario.class);
        for (Release1Scenario scenario : Release1Scenario.values()) {
            outcomes.put(scenario, Outcome.PASSED);
        }
        return outcomes;
    }

    @Test
    void theSuiteHasExactlyNineScenarios() {
        assertThat(Release1Scenario.values()).hasSize(9);
    }

    @Test
    void allNinePassedIsGreenAndReleaseOneMayClose() {
        SuiteVerdict verdict = SuiteVerdict.of(allPassed());

        assertThat(verdict.green()).isTrue();
        assertThat(verdict.releaseMayClose()).isTrue();
        assertThat(verdict.reasons()).isEmpty();
        assertThat(verdict.report()).contains("Verdict: GO").doesNotContain("NO-GO");
    }

    @Tag("negative")
    @ParameterizedTest
    @EnumSource(Release1Scenario.class)
    void oneRedScenarioMakesTheSuiteRedAndBlocksRelease(Release1Scenario red) {
        Map<Release1Scenario, Outcome> outcomes = allPassed();
        outcomes.put(red, Outcome.FAILED);

        SuiteVerdict verdict = SuiteVerdict.of(outcomes);

        assertThat(verdict.green()).as("one red of nine").isFalse();
        assertThat(verdict.releaseMayClose()).isFalse();
        assertThat(verdict.reasons()).containsExactly(red.id() + " FAILED");
        assertThat(verdict.report()).contains("Verdict: NO-GO").contains(red.id() + " FAILED");
    }

    @Tag("negative")
    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = "PASSED", mode = EnumSource.Mode.EXCLUDE)
    void aGatedAbortedOrUnrunScenarioIsNeverGreen(Outcome notPassed) {
        Map<Release1Scenario, Outcome> outcomes = allPassed();
        outcomes.put(Release1Scenario.SC04, notPassed);

        SuiteVerdict verdict = SuiteVerdict.of(outcomes);

        assertThat(verdict.green()).as("SC-04 %s", notPassed).isFalse();
        assertThat(verdict.releaseMayClose()).isFalse();
        assertThat(verdict.reasons()).containsExactly("SC-04 " + notPassed);
    }

    @Tag("negative")
    @Test
    void aScenarioWithNoRecordedOutcomeCountsAsNotRun() {
        Map<Release1Scenario, Outcome> outcomes = allPassed();
        outcomes.remove(Release1Scenario.SC07);

        SuiteVerdict verdict = SuiteVerdict.of(outcomes);

        assertThat(verdict.green()).isFalse();
        assertThat(verdict.reasons()).containsExactly("SC-07 NOT_RUN");
    }

    @Test
    void reasonsAreListedInScenarioOrder() {
        Map<Release1Scenario, Outcome> outcomes = allPassed();
        outcomes.put(Release1Scenario.SC09, Outcome.FAILED);
        outcomes.put(Release1Scenario.SC02, Outcome.DISABLED);

        assertThat(SuiteVerdict.of(outcomes).reasons()).containsExactly("SC-02 DISABLED", "SC-09 FAILED");
    }

    @Test
    void anEmptyRunIsRedForEveryScenario() {
        SuiteVerdict verdict = SuiteVerdict.of(Map.of());

        assertThat(verdict.green()).isFalse();
        assertThat(verdict.reasons()).hasSize(9);
    }

    @Test
    void aNullOutcomeMapIsRefused() {
        assertThatThrownBy(() -> SuiteVerdict.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theVerdictDoesNotChangeWhenTheCallerMutatesItsMapAfterwards() {
        Map<Release1Scenario, Outcome> outcomes = allPassed();
        SuiteVerdict verdict = SuiteVerdict.of(outcomes);

        outcomes.put(Release1Scenario.SC01, Outcome.FAILED);

        assertThat(verdict.green()).isTrue();
    }
}
