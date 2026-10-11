package io.integrationplatform.config.rule.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One evaluation: the engine's output as the decision model returned it, and - for a VALIDATION rule set - every
 * violation in table order (empty for ROUTING and LOOKUP).
 *
 * @param kind VALIDATION, ROUTING or LOOKUP
 */
public record RuleEvaluation(UUID ruleSetId, String kind, JsonNode output, List<RuleViolation> violations) {

    public RuleEvaluation {
        Objects.requireNonNull(ruleSetId, "ruleSetId");
        Objects.requireNonNull(kind, "kind");
        violations = List.copyOf(violations);
    }
}
