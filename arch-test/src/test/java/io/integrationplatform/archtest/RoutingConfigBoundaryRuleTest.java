package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * "Routing does not depend on config's application or adapter layers" (row 278 C2, ADR-017 Decision 6 and Gate 3).
 *
 * <pre>
 * Scenario: the production modules pass
 *   Given every module's main classes
 *   When the build runs the rule
 *   Then 0 routing classes depend on a config .application or .adapter package, FlowBundle excepted
 *
 * &#64;negative Scenario: a planted violation fails and is named
 *   Given a routing-scoped class that holds config's application ConfigApiException
 *   When the same rule object evaluates the planted package
 *   Then it reports exactly 1 violation naming the class and the type (the bite)
 * </pre>
 */
class RoutingConfigBoundaryRuleTest {

    private static final String FIXTURES = "io.integrationplatform.routing.archfixture";

    // Scenario: the production modules pass - the real scan over every module's main classes (Gate 3: 0).
    @Test
    void productionRoutingClassesUseNoConfigApplicationOrAdapterType() {
        JavaClasses production = RoutingConfigBoundaryRule.importProductionClasses();

        assertThat(production.containPackage("io.integrationplatform.routing")).isTrue();
        assertThat(production.containPackage("io.integrationplatform.config")).isTrue();

        EvaluationResult result = RoutingConfigBoundaryRule.ROUTING_USES_NO_CONFIG_APPLICATION_OR_ADAPTER
                .evaluate(production);
        assertThat(result.getFailureReport().getDetails()).as("0 violations on production classes").isEmpty();
    }

    @Test
    void productionScanExcludesThePlantedFixtures() {
        JavaClasses production = RoutingConfigBoundaryRule.importProductionClasses();

        assertThat(production.stream().map(c -> c.getName()))
                .as("no fixture or arch-test class in the production scan")
                .noneMatch(name -> name.contains(".archfixture.")
                        || name.startsWith("io.integrationplatform.archtest."));
    }

    // Gate 3: the exception list holds exactly one name.
    @Test
    void theRuleHasExactlyOneNamedException() {
        assertThat(RoutingConfigBoundaryRule.NAMED_EXCEPTIONS)
                .containsExactly("io.integrationplatform.config.flow.application.FlowBundle");
    }

    @Test
    void onlyConfigApplicationAndAdapterPackagesAreGuarded() {
        assertThat(RoutingConfigBoundaryRule.isConfigApplicationOrAdapter("io.integrationplatform.config.application"))
                .isTrue();
        assertThat(RoutingConfigBoundaryRule.isConfigApplicationOrAdapter("io.integrationplatform.config.adapter.in"))
                .isTrue();
        assertThat(RoutingConfigBoundaryRule.isConfigApplicationOrAdapter(
                "io.integrationplatform.config.flow.application")).isTrue();
        assertThat(RoutingConfigBoundaryRule.isConfigApplicationOrAdapter("io.integrationplatform.config.flow.api"))
                .isFalse();
        assertThat(RoutingConfigBoundaryRule.isConfigApplicationOrAdapter("io.integrationplatform.config.destination"))
                .isFalse();
        assertThat(RoutingConfigBoundaryRule.isConfigApplicationOrAdapter(
                "io.integrationplatform.routing.adapter.api")).as("routing's own adapters are not config's").isFalse();
        assertThat(RoutingConfigBoundaryRule.isNamedException(
                "io.integrationplatform.config.flow.application.FlowBundle$Parsed")).isTrue();
        assertThat(RoutingConfigBoundaryRule.isNamedException(
                "io.integrationplatform.config.flow.application.FlowBundleWriter")).isFalse();
    }

    // Scenario: the compliant fixture (flow.api types plus the named exception) passes.
    @Test
    void compliantApiAndNamedExceptionUsePasses() {
        JavaClasses compliant = new ClassFileImporter().importPackages(FIXTURES + ".compliant");

        assertThat(compliant.size()).as("the compliant fixture is in scope").isEqualTo(1);
        assertThat(RoutingConfigBoundaryRule.ROUTING_USES_NO_CONFIG_APPLICATION_OR_ADAPTER.evaluate(compliant)
                .getFailureReport().getDetails()).isEmpty();
    }

    // @negative Scenario: the plant bites - exactly 1 violation, naming the class and the config type (Gate 3: 1).
    @Test
    void aConfigApplicationTypeInRoutingIsNamedByTheRule() {
        JavaClasses planted = new ClassFileImporter().importPackages(FIXTURES + ".planted");

        assertThat(planted.size()).as("the plant is in scope").isEqualTo(1);
        EvaluationResult result = RoutingConfigBoundaryRule.ROUTING_USES_NO_CONFIG_APPLICATION_OR_ADAPTER
                .evaluate(planted);
        assertThat(result.getFailureReport().getDetails()).as("exactly 1 violation on the plant").hasSize(1);
        assertThat(result.getFailureReport().getDetails().get(0))
                .contains("PlantedRoutingConfigApplicationUse")
                .contains("io.integrationplatform.config.application.ConfigApiException");
    }
}
