package io.integrationplatform.config.rule.application;

import io.integrationplatform.config.rule.api.RuleEvaluation;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.api.RuleSetNotFoundException;
import io.integrationplatform.config.rule.api.RuleViolation;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository.RuleSetRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * {@link RuleEvaluationPort}: reads the stored rule set for the tenant of the current transaction and evaluates it
 * with the {@link DecisionEngine}. A VALIDATION rule set's matching rows become {@link RuleViolation}s in table order
 * (hitPolicy "collect" returns an array, one element per matching row; a single-hit policy returns one object).
 *
 * <p>ponytail: the decision is compiled per call; a cache keyed by rule set and update time comes when the data
 * plane's load asks for it.
 */
public class RuleEvaluationService implements RuleEvaluationPort {

    private final JdbcRuleSetRepository ruleSets;
    private final DecisionEngine engine;

    public RuleEvaluationService(JdbcRuleSetRepository ruleSets, DecisionEngine engine) {
        this.ruleSets = Objects.requireNonNull(ruleSets, "ruleSets");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    @Transactional(readOnly = true)
    public RuleEvaluation evaluate(UUID ruleSetId, JsonNode input) {
        RuleSetRow ruleSet = ruleSets.find(ruleSetId).orElseThrow(RuleSetNotFoundException::new);
        JsonNode output = engine.evaluate(ruleSet.content(), input);
        List<RuleViolation> violations = "VALIDATION".equals(ruleSet.kind()) ? violations(output) : List.of();
        return new RuleEvaluation(ruleSetId, ruleSet.kind(), output, violations);
    }

    private static List<RuleViolation> violations(JsonNode output) {
        List<RuleViolation> violations = new ArrayList<>();
        for (JsonNode row : output.isArray() ? output : List.of(output)) {
            JsonNode violation = row.path("violation");
            if (violation.isObject()) {
                violations.add(new RuleViolation(text(violation, "field"), text(violation, "code"),
                        text(violation, "message")));
            }
        }
        return violations;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
