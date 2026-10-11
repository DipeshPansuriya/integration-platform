package io.integrationplatform.config.rule.parity.planted.evaluator;

import io.integrationplatform.config.rule.api.RuleEvaluation;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * PLANTED (T-029 R02 fixture, never production): a "different engine" for the test panel - it answers like the
 * runtime but keeps only the FIRST violation (the first-error-only defect E6-C2 forbids). It agrees with the runtime
 * on every sample with 0 or 1 violation and disagrees on every sample with several, so the parity test must fail.
 */
public final class FirstViolationOnlyEvaluator implements RuleEvaluationPort {

    private final RuleEvaluationPort runtime;

    public FirstViolationOnlyEvaluator(RuleEvaluationPort runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public RuleEvaluation evaluate(UUID ruleSetId, JsonNode input) {
        RuleEvaluation full = runtime.evaluate(ruleSetId, input);
        return new RuleEvaluation(full.ruleSetId(), full.kind(), full.output(),
                full.violations().isEmpty() ? List.of() : List.of(full.violations().getFirst()));
    }
}
