package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * E9 T-177 "one slow target never stalls the others": the stage listener reaches a target call only through the
 * per-target bulkhead. The production scan proves every stage handler hands its send to the bulkhead; the planted
 * fixture proves a handler that calls the target on the consumer thread fails the build and is named.
 */
class StageListenerOnlyThroughBulkheadRuleTest {

    private static final String FIXTURES = "io.integrationplatform.e9.archfixture.bulkhead";

    @Test
    void productionStageHandlersSendOnlyThroughTheBulkhead() {
        JavaClasses production = CamelOnlyInRoutingRule.importProductionClasses();

        assertThat(production.containPackage("io.integrationplatform.routing.stage2.bulkhead"))
                .as("the bulkhead in scope").isTrue();

        StageListenerOnlyThroughBulkheadRule.STAGE_LISTENER_ONLY_THROUGH_BULKHEAD.check(production);
    }

    @Test
    void aHandlerThatHandsOffToTheBulkheadPasses() {
        JavaClasses compliant = new ClassFileImporter().importPackages(FIXTURES + ".compliant");

        assertThat(compliant.size()).as("the compliant fixture is in scope").isEqualTo(1);
        StageListenerOnlyThroughBulkheadRule.STAGE_LISTENER_ONLY_THROUGH_BULKHEAD.check(compliant);
    }

    @Test
    void aHandlerThatCallsTheTargetPastTheBulkheadFailsAndIsNamed() {
        JavaClasses planted = new ClassFileImporter().importPackages(FIXTURES + ".planted");

        assertThat(planted.size()).as("the planted violation is in scope").isEqualTo(1);
        assertThatThrownBy(() -> StageListenerOnlyThroughBulkheadRule.STAGE_LISTENER_ONLY_THROUGH_BULKHEAD.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedCallPastBulkhead")
                .hasMessageContaining("TargetCall.call");
    }
}
