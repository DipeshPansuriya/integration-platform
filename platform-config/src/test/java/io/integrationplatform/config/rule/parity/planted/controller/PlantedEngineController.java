package io.integrationplatform.config.rule.parity.planted.controller;

import io.gorules.zen_engine.JsonBuffer;
import io.gorules.zen_engine.ZenDecision;
import io.gorules.zen_engine.ZenEngine;
import java.util.Objects;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * PLANTED (T-029 R02 fixture, never production): a test-panel evaluate endpoint that imports the ZEN engine and
 * evaluates with it directly instead of going through {@code RuleEvaluationPort}. EvaluateThroughPortArchitectureTest
 * runs its guard over this package and must report it.
 *
 * <p>Carries no {@code @RestController} on purpose: it lives inside {@code io.integrationplatform.config}, which the
 * suite apps component-scan, and must never be registered as a real endpoint. The guard finds the endpoint by its
 * {@code @PostMapping(".../evaluate")} method, the same way it finds the production one.
 */
public class PlantedEngineController {

    private final ZenEngine engine;

    public PlantedEngineController(ZenEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @PostMapping("/api/v1/admin/rule-sets/{ruleSetId}/evaluate")
    public Object evaluateRuleSet(@PathVariable String ruleSetId, @RequestBody String jdmAndInput) throws Exception {
        try (ZenDecision decision = engine.createDecision(new JsonBuffer(jdmAndInput))) {
            return decision.evaluate(new JsonBuffer("{}"), null).get().result();
        }
    }
}
