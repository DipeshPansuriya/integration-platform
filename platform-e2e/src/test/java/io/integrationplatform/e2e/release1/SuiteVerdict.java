package io.integrationplatform.e2e.release1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Row 323 T139: the Release 1 verdict. GREEN only when all nine scenarios PASSED. One FAILED, DISABLED (gated),
 * ABORTED or NOT_RUN scenario makes the suite RED, and Release 1 may not close.
 */
public final class SuiteVerdict {

    public enum Outcome { PASSED, FAILED, DISABLED, ABORTED, NOT_RUN }

    private final Map<Release1Scenario, Outcome> outcomes;
    private final List<String> reasons;

    private SuiteVerdict(Map<Release1Scenario, Outcome> outcomes) {
        this.outcomes = outcomes;
        List<String> red = new ArrayList<>();
        outcomes.forEach((scenario, outcome) -> {
            if (outcome != Outcome.PASSED) {
                red.add(scenario.id() + " " + outcome);
            }
        });
        this.reasons = Collections.unmodifiableList(red);
    }

    public static SuiteVerdict of(Map<Release1Scenario, Outcome> recorded) {
        Objects.requireNonNull(recorded, "outcomes");
        Map<Release1Scenario, Outcome> all = new EnumMap<>(Release1Scenario.class);
        for (Release1Scenario scenario : Release1Scenario.values()) {
            all.put(scenario, recorded.getOrDefault(scenario, Outcome.NOT_RUN));
        }
        return new SuiteVerdict(all);
    }

    public boolean green() {
        return reasons.isEmpty();
    }

    public boolean releaseMayClose() {
        return green();
    }

    /** One line per non-passed scenario, in scenario order: "SC-04 DISABLED". */
    public List<String> reasons() {
        return reasons;
    }

    public String report() {
        StringBuilder text = new StringBuilder("Release 1 suite (E15-F1-S1)\n");
        outcomes.forEach((scenario, outcome) -> text.append("  ").append(scenario.id()).append(' ')
                .append(outcome).append(" - ").append(scenario.title()).append('\n'));
        text.append(green() ? "Verdict: GO (all nine passed)" : "Verdict: NO-GO - " + String.join("; ", reasons));
        return text.toString();
    }
}
