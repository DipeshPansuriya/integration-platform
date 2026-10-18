package io.integrationplatform.e2e.release1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Row 323 T139 @negative wiring: what the release gate in {@code Release1ScenariosContainerTest} sees. One scenario
 * recorded red (failed or gated) keeps the gate RED; only nine PASSED make it GO.
 */
class OutcomeRecorderTest {

    @ScenarioId(Release1Scenario.SC01)
    void sc01() {
    }

    @ScenarioId(Release1Scenario.SC04)
    void sc04() {
    }

    void notAScenario() {
    }

    private static ExtensionContext contextOf(String methodName) throws NoSuchMethodException {
        Method method = OutcomeRecorderTest.class.getDeclaredMethod(methodName);
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getTestMethod()).thenReturn(Optional.of(method));
        return context;
    }

    @Test
    void eachCallbackRecordsItsOutcome() throws Exception {
        OutcomeRecorder recorder = new OutcomeRecorder();
        recorder.testSuccessful(contextOf("sc01"));
        recorder.testDisabled(contextOf("sc04"), Optional.of("waits on T111"));

        assertThat(recorder.outcomes()).containsEntry(Release1Scenario.SC01, SuiteVerdict.Outcome.PASSED)
                .containsEntry(Release1Scenario.SC04, SuiteVerdict.Outcome.DISABLED).hasSize(2);
    }

    @Test
    void failedAndAbortedAreRecordedRed() throws Exception {
        OutcomeRecorder recorder = new OutcomeRecorder();
        recorder.testFailed(contextOf("sc01"), new AssertionError("x"));
        recorder.testAborted(contextOf("sc04"), new RuntimeException("y"));

        assertThat(recorder.outcomes()).containsEntry(Release1Scenario.SC01, SuiteVerdict.Outcome.FAILED)
                .containsEntry(Release1Scenario.SC04, SuiteVerdict.Outcome.ABORTED);
    }

    @Test
    void aMethodWithoutScenarioIdIsIgnored() throws Exception {
        OutcomeRecorder recorder = new OutcomeRecorder();
        recorder.testSuccessful(contextOf("notAScenario"));
        assertThat(recorder.outcomes()).isEmpty();
    }

    @Tag("negative")
    @Test
    void negativeOneRedScenarioKeepsTheGateRedEvenIfTheOtherEightPass() throws Exception {
        OutcomeRecorder recorder = new OutcomeRecorder();
        recorder.testFailed(contextOf("sc04"), new AssertionError("replay lost pinned v1.0"));
        // SC01 passes; the other seven never report -> NOT_RUN. Fill them as PASSED via a full map to isolate SC-04.
        java.util.Map<Release1Scenario, SuiteVerdict.Outcome> eightGreen = new java.util.EnumMap<>(Release1Scenario.class);
        for (Release1Scenario scenario : Release1Scenario.values()) {
            eightGreen.put(scenario, SuiteVerdict.Outcome.PASSED);
        }
        eightGreen.putAll(recorder.outcomes());

        SuiteVerdict verdict = SuiteVerdict.of(eightGreen);
        assertThat(verdict.green()).isFalse();
        assertThat(verdict.releaseMayClose()).isFalse();
        assertThat(verdict.reasons()).containsExactly("SC-04 FAILED");
        assertThat(verdict.report()).endsWith("Verdict: NO-GO - SC-04 FAILED");
    }

    @Test
    void aFreshRecorderGivesNoGoWithNineNotRun() {
        assertThat(new OutcomeRecorder().verdict().reasons()).hasSize(9).allMatch(reason -> reason.endsWith("NOT_RUN"));
    }
}
