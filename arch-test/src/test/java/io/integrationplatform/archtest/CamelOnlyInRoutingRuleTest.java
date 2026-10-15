package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * E9-F1-S2 "Camel outside its module fails the build" (T-121; R12, ADR-010; E9-C6).
 *
 * <pre>
 * Scenario: Compliant modules pass
 *   Given the routing core under io.integrationplatform.routing and every non-routing module without a Camel import
 *   When the build runs the ArchUnit rule
 *   Then it passes
 *
 * &#64;negative
 * Scenario: Camel outside its module fails the build
 *   Given any class outside platform-routing importing org.apache.camel
 *   When the build runs
 *   Then ArchUnit fails the build and names the class
 * </pre>
 *
 * <p>The production run imports every module's production classes under {@code io.integrationplatform..} (test
 * classes excluded, so the planted fixtures never reach it). The fixture runs check the SAME rule object against
 * the planted and compliant fixture packages: the violation must fail ONLY against the deliberate violation
 * (red-first proven by the previous commit's bare check, d97d1308 - 5 violations, all against the planted class).
 */
class CamelOnlyInRoutingRuleTest {

    private static final String FIXTURES = "io.integrationplatform.e9.archfixture";

    // Scenario: Compliant modules pass - the real scan over every module's production classes.
    @Test
    void productionClassesOfEveryModulePassTheCamelRule() {
        JavaClasses production = CamelOnlyInRoutingRule.importProductionClasses();

        assertThat(production.contain("io.integrationplatform.IntegrationPlatformApplication"))
                .as("platform-app in scope").isTrue();
        assertThat(production.containPackage("io.integrationplatform.routing"))
                .as("platform-routing in scope").isTrue();

        CamelOnlyInRoutingRule.CAMEL_ONLY_IN_ROUTING.check(production);
    }

    // Scenario: Compliant modules pass - a compliant class outside the routing core (no Camel import) passes.
    @Test
    void compliantClassOutsideRoutingPasses() {
        JavaClasses compliant = new ClassFileImporter().importPackages(FIXTURES + ".compliant");

        assertThat(compliant.size()).as("the compliant fixture is in scope").isEqualTo(1);
        CamelOnlyInRoutingRule.CAMEL_ONLY_IN_ROUTING.check(compliant);
    }

    // @negative Scenario: Camel outside its module fails the build - and names the offending class.
    @Test
    void camelOutsideRoutingFailsAndNamesTheClass() {
        JavaClasses planted = new ClassFileImporter().importPackages(FIXTURES + ".planted");

        assertThat(planted.size()).as("the planted violation is in scope").isEqualTo(1);

        assertThatThrownBy(() -> CamelOnlyInRoutingRule.CAMEL_ONLY_IN_ROUTING.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedCamelOutsideRouting")
                .hasMessageContaining("org.apache.camel");
    }

    // @negative Scenario: the rule refused ONLY the deliberate violation - the failure report carries no
    // compliant class of any package.
    @Test
    void theReportNamesOnlyThePlantedViolation() {
        JavaClasses planted = new ClassFileImporter()
                .importPackages(FIXTURES + ".planted", FIXTURES + ".compliant");

        var result = CamelOnlyInRoutingRule.CAMEL_ONLY_IN_ROUTING.evaluate(planted);

        assertThat(result.getFailureReport().getDetails())
                .as("the report names only the planted violation")
                .anyMatch(line -> line.contains("PlantedCamelOutsideRouting") && line.contains("org.apache.camel"))
                .noneMatch(line -> line.contains("PlainOutsideRoutingService"));
    }
}