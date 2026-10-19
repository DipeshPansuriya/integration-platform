package io.integrationplatform.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.lang.ArchRule;
import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetCall;
import org.apache.camel.ProducerTemplate;

/**
 * T-177 "one slow target never stalls the others" (E9): the stage listener ({@code StageConsumer} and every
 * {@code StageConsumer.Handler}) never calls a target itself, neither through {@code TargetCall} nor a Camel
 * {@code ProducerTemplate} send. A send reaches {@code TargetCall} only through the
 * per-target bulkhead ({@code io.integrationplatform.routing.stage2.bulkhead..}), so a slow target holds its own
 * pool, never the consumer thread.
 */
final class StageListenerOnlyThroughBulkheadRule {

    static final String BULKHEAD_PACKAGE = "io.integrationplatform.routing.stage2.bulkhead..";

    // shortcut: matches calls declared on TargetCall or ProducerTemplate, not on a concrete adapter; widen if one appears
    static final ArchRule STAGE_LISTENER_ONLY_THROUGH_BULKHEAD = noClasses()
            .that().implement(StageConsumer.Handler.class)
            .or().resideInAPackage("io.integrationplatform.routing.stage")
            .and().resideOutsideOfPackages(BULKHEAD_PACKAGE)
            .should().callMethod(TargetCall.class, "call", Stage2Send.class)
            .orShould().callMethodWhere(JavaCall.Predicates.target(AccessTarget.Predicates.declaredIn(ProducerTemplate.class)))
            .because("T-177: the stage listener reaches a target call only through the per-target bulkhead ("
                    + BULKHEAD_PACKAGE + ")");

    private StageListenerOnlyThroughBulkheadRule() {
    }
}
