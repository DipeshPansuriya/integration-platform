package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * "No module imports another module's internal package" (row 278 E7 B2 follow-up, mahesh-architect's finding:
 * platform-config's MappingTestConfiguration constructed transform's internal DashjoinJsonataTransformPort).
 *
 * <pre>
 * Scenario: the production modules pass
 *   Given every module's main classes
 *   When the build runs the rule
 *   Then no class depends on another module's .internal package
 *
 * &#64;negative Scenario: a planted violation fails and is named
 *   Given a config-scoped class that constructs transform's internal engine (PlantedConfigTransformInternalWiring)
 *   When the same rule object evaluates the planted package
 *   Then it fails the build and names the violating class and the internal type (the bite)
 * </pre>
 */
class ModuleInternalsRuleTest {

    private static final String FIXTURES = "io.integrationplatform.config.archfixture.moduleinternal";

    // Scenario: the production modules pass - the real scan over every module's main classes.
    @Test
    void productionClassesImportNoOtherModulesInternals() {
        JavaClasses production = ModuleInternalsRule.importProductionClasses();

        assertThat(production.containPackage("io.integrationplatform.config")).isTrue();
        assertThat(production.containPackage("io.integrationplatform.transform")).isTrue();

        ModuleInternalsRule.NO_MODULE_IMPORTS_ANOTHER_MODULES_INTERNALS.check(production);
    }

    @Test
    void productionScanExcludesThePlantedFixtures() {
        JavaClasses production = ModuleInternalsRule.importProductionClasses();

        assertThat(production.stream().map(c -> c.getName()))
                .as("no fixture or arch-test class in the production scan")
                .noneMatch(name -> name.contains(".archfixture.")
                        || name.startsWith("io.integrationplatform.archtest."));
    }

    @Test
    void moduleIsTheThirdPackageSegment() {
        assertThat(ModuleInternalsRule.moduleOf("io.integrationplatform.config.mapping.internal")).isEqualTo("config");
        assertThat(ModuleInternalsRule.moduleOf("io.integrationplatform.transform")).isEqualTo("transform");
        assertThat(ModuleInternalsRule.moduleOf("org.springframework.context")).isEmpty();
        assertThat(ModuleInternalsRule.isInternal("io.integrationplatform.transform.internal")).isTrue();
        assertThat(ModuleInternalsRule.isInternal("io.integrationplatform.tenancy.internal.persistence")).isTrue();
        assertThat(ModuleInternalsRule.isInternal("io.integrationplatform.transform.api")).isFalse();
        assertThat(ModuleInternalsRule.isInternal("io.integrationplatform.config.internalish")).isFalse();
    }

    // Scenario: the compliant fixture (api port only) passes.
    @Test
    void compliantApiPortUsePasses() {
        JavaClasses compliant = new ClassFileImporter().importPackages(FIXTURES + ".compliant");

        assertThat(compliant.size()).as("the compliant fixture is in scope").isEqualTo(1);
        ModuleInternalsRule.NO_MODULE_IMPORTS_ANOTHER_MODULES_INTERNALS.check(compliant);
    }

    // @negative Scenario: the plant bites - asserted as expected (the TransformNoCamelRuleTest convention).
    @Test
    void transformInternalInConfigIsNamedByTheRule() {
        JavaClasses planted = new ClassFileImporter().importPackages(FIXTURES + ".planted");

        assertThat(planted.size()).as("the plant is in scope").isEqualTo(1);
        assertThatThrownBy(() -> ModuleInternalsRule.NO_MODULE_IMPORTS_ANOTHER_MODULES_INTERNALS.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedConfigTransformInternalWiring")
                .hasMessageContaining("io.integrationplatform.transform.internal.DashjoinJsonataTransformPort");
    }
}
