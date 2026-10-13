package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * E7-C8 "transform never imports Camel" (T66-adjacent, amit-developer; sanjay-developer's recommendation) and the
 * engine-only direction against config's mapping internals.
 *
 * <pre>
 * Scenario: the production transform module passes
 *   Given the platform-transform main classes (the JSONata engine through Dashjoin's port, ADR-006)
 *   When the build runs the two rules
 *   Then they pass - no import of org.apache.camel, no import of config's mapping internals
 *
 * &#64;negative Scenario: a planted violation fails and is named
 *   Given a transform-scoped class that imports org.apache.camel (PlantedTransformCamelImport)
 *     or config's mapping internals (PlantedTransformConfigCrossing)
 *   When the same rule objects evaluate the planted packages
 *   Then they fail the build and name the violating class (the bite)
 * </pre>
 */
class TransformNoCamelRuleTest {

    private static final String FIXTURES = "io.integrationplatform.transform.archfixture";

    // Scenario: the production transform module passes - the real scan over every module's main classes.
    @Test
    void productionClassesPassBothTransformGuards() {
        JavaClasses production = TransformNoCamelRule.importProductionClasses();

        assertThat(production.containPackage("io.integrationplatform.transform"))
                .as("platform-transform is in the scanned scope").isTrue();

        TransformNoCamelRule.TRANSFORM_NEVER_IMPORTS_CAMEL.check(production);
        TransformNoCamelRule.TRANSFORM_IMPORTS_NO_CONFIG_MAPPING_INTERNALS.check(production);
    }

    @Test
    void productionScanExcludesThePlantedFixtures() {
        JavaClasses production = TransformNoCamelRule.importProductionClasses();

        assertThat(production.contain("io.integrationplatform.transform.archfixture.planted"
                + ".PlantedTransformCamelImport"))
                .as("test fixtures never reach the production scan").isFalse();
        assertThat(production.stream().map(c -> c.getName()))
                .as("no fixture or arch-test class in the production scan")
                .noneMatch(name -> name.contains(".archfixture.")
                        || name.startsWith("io.integrationplatform.archtest."));
    }

    // Scenario: the compliant engine fixture passes both guards.
    @Test
    void compliantEngineFixturePasses() {
        JavaClasses compliant = new ClassFileImporter().importPackages(FIXTURES + ".compliant");

        assertThat(compliant.size()).as("the compliant fixture is in scope").isEqualTo(1);
        TransformNoCamelRule.TRANSFORM_NEVER_IMPORTS_CAMEL.check(compliant);
        TransformNoCamelRule.TRANSFORM_IMPORTS_NO_CONFIG_MAPPING_INTERNALS.check(compliant);
    }

    // @negative Scenario: the plants bite - wip red: the direct check() calls fail while
    // the plants are present; once the bite is proven these become assertThatThrownBy assertions
    // (the TenantFilterRuleTest convention) and the run goes green.
    @Test
    void camelImportInTransformIsNamedByTheRule() {
        JavaClasses planted = new ClassFileImporter().importPackages(FIXTURES + ".planted");

        assertThat(planted.size()).as("both plants are in scope").isEqualTo(2);
        TransformNoCamelRule.TRANSFORM_NEVER_IMPORTS_CAMEL.check(planted);
    }

    @Test
    void configMappingInternalInTransformIsNamedByTheRule() {
        JavaClasses planted = new ClassFileImporter().importPackages(FIXTURES + ".planted");

        TransformNoCamelRule.TRANSFORM_IMPORTS_NO_CONFIG_MAPPING_INTERNALS.check(planted);
    }
}