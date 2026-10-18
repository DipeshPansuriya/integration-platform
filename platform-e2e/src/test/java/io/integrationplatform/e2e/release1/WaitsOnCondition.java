package io.integrationplatform.e2e.release1;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Row 323 T139/T140: disables a test or class whose {@link WaitsOn} items are not all on main. A disabled scenario
 * is reported as such, never as passed: {@link SuiteVerdict} counts it red.
 */
public final class WaitsOnCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<WaitsOn> waitsOn = context.getElement()
                .flatMap(element -> AnnotationSupport.findAnnotation(element, WaitsOn.class));
        return evaluate(waitsOn, Release1Pending.ON_MAIN);
    }

    static ConditionEvaluationResult evaluate(Optional<WaitsOn> waitsOn, Map<String, Boolean> flags) {
        if (waitsOn.isEmpty()) {
            return ConditionEvaluationResult.enabled("no @WaitsOn");
        }
        return Release1Pending.waitsOn(flags, waitsOn.get().value())
                .map(ConditionEvaluationResult::disabled)
                .orElseGet(() -> ConditionEvaluationResult.enabled("every item is on main"));
    }
}
