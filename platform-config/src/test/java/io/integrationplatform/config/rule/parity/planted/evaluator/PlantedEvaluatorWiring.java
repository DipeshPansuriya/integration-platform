package io.integrationplatform.config.rule.parity.planted.evaluator;

import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * PLANTED (T-029 R02 fixture): wires the test panel's endpoint to {@link FirstViolationOnlyEvaluator} by making it
 * the primary {@link RuleEvaluationPort}; the real port stays registered under its production name
 * {@code ruleEvaluationPort}. Carries no stereotype on purpose, so no component scan of
 * {@code io.integrationplatform.config} ever picks it up - only DifferentEngineParityContainerTest registers it.
 */
public class PlantedEvaluatorWiring {

    @Primary
    @Bean
    public RuleEvaluationPort plantedFirstViolationOnlyEvaluator(
            @Qualifier("ruleEvaluationPort") RuleEvaluationPort runtime) {
        return new FirstViolationOnlyEvaluator(runtime);
    }
}
