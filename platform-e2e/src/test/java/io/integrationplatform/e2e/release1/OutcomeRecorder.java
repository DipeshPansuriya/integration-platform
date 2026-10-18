package io.integrationplatform.e2e.release1;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Row 323 T139: records each {@link ScenarioId} test's outcome so the final gate can build the {@link SuiteVerdict}.
 * A disabled (gated) scenario is recorded DISABLED, never PASSED. Methods without {@link ScenarioId} are ignored.
 */
public final class OutcomeRecorder implements TestWatcher {

    private final Map<Release1Scenario, SuiteVerdict.Outcome> outcomes = new EnumMap<>(Release1Scenario.class);

    @Override
    public void testSuccessful(ExtensionContext context) {
        record(context, SuiteVerdict.Outcome.PASSED);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        record(context, SuiteVerdict.Outcome.FAILED);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        record(context, SuiteVerdict.Outcome.ABORTED);
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        record(context, SuiteVerdict.Outcome.DISABLED);
    }

    public synchronized Map<Release1Scenario, SuiteVerdict.Outcome> outcomes() {
        return Collections.unmodifiableMap(new EnumMap<>(outcomes));
    }

    public SuiteVerdict verdict() {
        return SuiteVerdict.of(outcomes());
    }

    private synchronized void record(ExtensionContext context, SuiteVerdict.Outcome outcome) {
        context.getTestMethod()
                .map(method -> method.getAnnotation(ScenarioId.class))
                .ifPresent(id -> outcomes.put(id.value(), outcome));
    }
}
