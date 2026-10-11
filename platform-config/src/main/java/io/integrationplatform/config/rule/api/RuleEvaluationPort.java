package io.integrationplatform.config.rule.api;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The configuration module's read API for rules (architecture.md section 2): evaluates one stored rule set on an
 * input with the ZEN engine (ADR-003). Used by the data plane (lane A's ingest validation, routing conditions) and by
 * the console's test panel, so the panel and the runtime run the same engine on the same stored table (E6-F3-S1).
 *
 * <p>The tenant is the one bound to the current transaction ({@code TenantContext}); a rule set of another tenant, or
 * an unknown id, throws {@link RuleSetNotFoundException} - indistinguishably.
 */
public interface RuleEvaluationPort {

    RuleEvaluation evaluate(UUID ruleSetId, JsonNode input);
}
