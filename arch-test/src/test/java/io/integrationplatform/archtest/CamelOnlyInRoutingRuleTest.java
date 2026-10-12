package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * RED-FIRST STEP (T-121, E9-F1-S2 @negative "Camel outside its module fails the build"): this running state
 * asserts the refusal BARE - the planted violation under io.integrationplatform.e9.archfixture.planted is checked
 * with no wrapper, so THIS RUN FAILS THE BUILD naming the class and org.apache.camel. The next commit hardens the
 * assertion into the refusal the rule must refuse with (R02 red-green pattern of the tenant filter rule).
 */
class CamelOnlyInRoutingRuleTest {

    private static final String FIXTURES = "io.integrationplatform.e9.archfixture";

    // Scenario: Compliant modules pass - the production scan of every module.
    @Test
    void productionClassesOfEveryModulePassTheCamelRule() {
        JavaClasses production = CamelOnlyInRoutingRule.importProductionClasses();

        assertThat(production.contain("io.integrationplatform.IntegrationPlatformApplication"))
                .as("platform-app in scope").isTrue();
        assertThat(production.containPackage("io.integrationplatform.routing"))
                .as("platform-routing in scope").isTrue();

        CamelOnlyInRoutingRule.CAMEL_ONLY_IN_ROUTING.check(production);
    }

    // @negative Scenario: Camel outside its module fails the build - RED STEP: the bare check.
    @Test
    void camelOutsideRoutingFailsTheBuild() {
        JavaClasses planted = new ClassFileImporter().importPackages(FIXTURES + ".planted");

        assertThat(planted.size()).as("planted violation fixture").isEqualTo(1);

        CamelOnlyInRoutingRule.CAMEL_ONLY_IN_ROUTING.check(planted);
    }
}