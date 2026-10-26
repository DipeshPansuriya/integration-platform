package io.integrationplatform.e9.perf;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 1 s routing budget and its step names (T-122, E9-F1-S3; E9-C8; figure "routing budget 1 s").
 *
 * <p>Each message's routing step - {@link Step#LOAD} (the accepted record and the message row), {@link
 * Step#EVALUATE_CONDITIONS} (the rule conditions selecting the routes) and {@link Step#CREATE_DELIVERIES}
 * (the pinned {@code io.integrationplatform.delivery.port.Stage1Deliveries#insertPerRoute} fan-out) - must finish
 * under 1 s; the failure report names the slow step by name, never a bare timing. Test-scope logic owned by lane
 * D; when lane C lands, the stage-1 path records its per-step boundaries through {@link #record}; until then the
 * perf test records the whole-step upper bound through {@link #recordUpperBound} (always reported as an upper
 * bound, never as a bare step name).
 */
final class RoutingStepBudget {

    /** The budget the story fixes: at most 1 second per message per routing step. */
    static final Duration STEP_BUDGET = Duration.ofSeconds(1);

    /** The routing steps of E9-F1-S3, in one message's order. */
    enum Step {
        /** Load the accepted record and the message row. */
        LOAD,

        /** Evaluate the route conditions (the rule evaluation selecting the routes). */
        EVALUATE_CONDITIONS,

        /** Create the pinned deliveries (one insert per route, ADR-014). */
        CREATE_DELIVERIES
    }

    private record Measurement(UUID messageId, String stepName, Duration elapsed) {
    }

    private final Map<UUID, List<Measurement>> byMessage = new ConcurrentHashMap<>();

    /** T-122 AC 3: the drain sub-step attribution lines the perf test read from the probes, surfaced in the failure. */
    private final List<String> drainAttribution = new java.util.concurrent.CopyOnWriteArrayList<>();

    void addDrainAttribution(String line) {
        drainAttribution.add(line);
    }

    /** Records one named step's measured duration for one message. */
    void record(UUID messageId, Step step, Duration elapsed) {
        recordMeasurable(messageId, step.name(), elapsed, " took ");
    }

    /**
     * Records the whole routing step of one message as observed end-to-end (produce-to-routed) - an upper bound
     * that stands in for the per-step boundaries while no stage-1 per-step probe is wired (wave-0 note in the
     * perf test); every report names it as an upper bound, never as a bare step name.
     */
    void recordUpperBound(UUID messageId, Duration produceToRouted) {
        recordMeasurable(messageId, "whole routing step (upper bound, no per-step probe wired)",
                produceToRouted, " of ");
    }

    private void recordMeasurable(UUID messageId, String stepName, Duration elapsed, String wording) {
        byMessage.computeIfAbsent(messageId, id -> new ArrayList<>())
                .add(new Measurement(messageId, wording + stepName, elapsed));
    }

    /**
     * Fails naming the slowest step when any recorded step went over budget - the perf test's assert. It also
     * refuses to pass on zero observations: no step recorded means the budget was never measured, not met.
     */
    void checkWithinBudget() {
        if (byMessage.isEmpty()) {
            throw new AssertionError("no routing step was measured - the stage-1 probes (T-117) recorded"
                    + " nothing, so the budget assert must not pass on zero observations");
        }
        List<String> violations = overBudget();
        if (!violations.isEmpty()) {
            String attribution = drainAttribution.isEmpty() ? ""
                    : " | drain attribution: " + String.join(" ; ", drainAttribution);
            throw new AssertionError("Routing over the " + STEP_BUDGET.toMillis() + " ms budget. Slowest first: "
                    + violations + attribution);
        }
    }

    /** Every recording over budget as its named line, slowest last; empty when all inside the budget. */
    List<String> overBudget() {
        List<Measurement> over = new ArrayList<>();
        for (List<Measurement> steps : byMessage.values()) {
            for (Measurement step : steps) {
                if (step.elapsed().compareTo(STEP_BUDGET) > 0) {
                    over.add(step);
                }
            }
        }
        over.sort(Comparator.comparing(Measurement::elapsed).reversed());
        List<String> named = new ArrayList<>();
        for (Measurement step : over) {
            named.add("step " + step.stepName() + " on message " + shortId(step.messageId()) + " took "
                    + step.elapsed().toMillis() + " ms (budget " + STEP_BUDGET.toMillis() + " ms)");
        }
        return named;
    }

    private static String shortId(UUID messageId) {
        return messageId.toString().substring(0, 8);
    }
}