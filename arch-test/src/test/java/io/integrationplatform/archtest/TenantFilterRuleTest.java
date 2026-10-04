package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * E1-F2-S1 "No query without the tenant filter" (traces E1-C3, E1-C5; R08, ADR-007 Decision 6 control 3).
 *
 * <pre>
 * Scenario: Compliant queries pass
 *   Given repository queries in every module that carry the tenant predicate
 *   When the build runs the ArchUnit rule
 *   Then it passes
 *
 * &#64;negative &#64;tenant
 * Scenario Outline: A query without the tenant predicate fails the build
 *   Given a &lt;query kind&gt; without the tenant predicate in module &lt;module&gt;
 *   When the build runs
 *   Then the ArchUnit rule fails the build and names the class and method
 *   Examples:
 *     | query kind        | module            |
 *     | repository method | platform-config   |
 *     | raw JDBC call     | platform-delivery |
 *     | native SQL string | a brand-new module |
 * </pre>
 *
 * <p>The production run imports every module's production classes under {@code io.integrationplatform..} (test classes
 * excluded, so the planted fixtures of this module never reach it). The fixture runs check the SAME rule objects
 * against the planted packages and assert that they fail.
 */
class TenantFilterRuleTest {

    private static final String FIXTURES = "io.integrationplatform";

    // Scenario: Compliant queries pass - the real scan over every module's production classes.
    @Test
    void productionClassesOfEveryModulePassTheTenantFilterRule() {
        JavaClasses production = TenantFilterRule.importProductionClasses();

        TenantFilterRule.NO_QUERY_WITHOUT_TENANT_FILTER.check(production);
        TenantFilterRule.BOUNDARY_CROSSINGS_ARE_REVIEWED.check(production);
    }

    @Test
    void productionScanCoversEveryModuleAndNoFixture() {
        JavaClasses production = TenantFilterRule.importProductionClasses();

        assertThat(production.contain("io.integrationplatform.IntegrationPlatformApplication"))
                .as("platform-app in scope").isTrue();
        assertThat(production.contain("io.integrationplatform.tenancy.TenantScopedRepository"))
                .as("platform-tenancy in scope").isTrue();
        assertThat(production.containPackage("io.integrationplatform.routing")).as("platform-routing in scope").isTrue();
        assertThat(production.containPackage("io.integrationplatform.ingest")).as("platform-ingest in scope").isTrue();
        assertThat(production.stream().map(c -> c.getName()))
                .as("no planted fixture or test class in the production scan")
                .noneMatch(name -> name.contains(".archfixture.") || name.startsWith("io.integrationplatform.archtest."));
    }

    // Scenario: Compliant queries pass - compliant repositories in config, delivery and a brand-new module.
    @Test
    void compliantRepositoriesInEveryModulePass() {
        JavaClasses compliant = new ClassFileImporter().importPackages(
                FIXTURES + ".config.archfixture.compliant",
                FIXTURES + ".delivery.archfixture.compliant",
                FIXTURES + ".brandnew.archfixture.compliant");

        assertThat(compliant.size()).isEqualTo(3);
        TenantFilterRule.NO_QUERY_WITHOUT_TENANT_FILTER.check(compliant);
    }

    // Scenario Outline: A query without the tenant predicate fails the build (and names the class and method).
    @ParameterizedTest(name = "{0} in {1}")
    @CsvSource({
            "repository method, platform-config,    io.integrationplatform.config.archfixture.planted,   PlantedRouteRepository.findRouteNames()",
            "raw JDBC call,     platform-delivery,  io.integrationplatform.delivery.archfixture.planted, PlantedDeliveryJdbcReader.countDeliveries()",
            "native SQL string, a brand-new module, io.integrationplatform.brandnew.archfixture.planted, PlantedNativeSqlQuery.loadEverything()"
    })
    void queryWithoutTenantPredicateFailsAndNamesClassAndMethod(String queryKind, String module, String plantedPackage,
            String classAndMethod) {
        JavaClasses planted = new ClassFileImporter().importPackages(plantedPackage);
        assertThat(planted.size()).as("fixture for %s in %s", queryKind, module).isPositive();

        assertThatThrownBy(() -> TenantFilterRule.NO_QUERY_WITHOUT_TENANT_FILTER.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Method <" + plantedPackage + "." + classAndMethod + ">");
    }

    @Test
    void repositoryTypeOutsideTheBaseIsNamed() {
        JavaClasses planted = new ClassFileImporter().importPackages(FIXTURES + ".config.archfixture.planted");

        EvaluationResult result = TenantFilterRule.NO_QUERY_WITHOUT_TENANT_FILTER.evaluate(planted);

        assertThat(result.getFailureReport().getDetails())
                .anyMatch(line -> line.contains("PlantedRouteRepository") && line.contains("JdbcDaoSupport"));
    }

    @Test
    void reviewedBoundaryCrossingsInTenancyInternalPass() {
        JavaClasses crossings = new ClassFileImporter()
                .importPackages(FIXTURES + ".tenancy.internal.archfixture.crossing");

        assertThat(crossings.size()).isEqualTo(2);
        TenantFilterRule.NO_QUERY_WITHOUT_TENANT_FILTER.check(crossings);
        TenantFilterRule.BOUNDARY_CROSSINGS_ARE_REVIEWED.check(crossings);
    }

    @Test
    void boundaryCrossingWithUnlistedReasonFails() {
        JavaClasses crossing = new ClassFileImporter()
                .importPackages(FIXTURES + ".tenancy.internal.archfixture.badcrossing");

        assertThatThrownBy(() -> TenantFilterRule.NO_QUERY_WITHOUT_TENANT_FILTER.check(crossing))
                .hasMessageContaining("PlantedUnlistedReasonCrossing.readAllTenants()");
        assertThatThrownBy(() -> TenantFilterRule.BOUNDARY_CROSSINGS_ARE_REVIEWED.check(crossing))
                .hasMessageContaining("PlantedUnlistedReasonCrossing");
    }

    @Test
    void boundaryCrossingOutsideTenancyInternalFails() {
        JavaClasses crossing = new ClassFileImporter()
                .importPackages(FIXTURES + ".delivery.archfixture.badcrossing");

        assertThatThrownBy(() -> TenantFilterRule.NO_QUERY_WITHOUT_TENANT_FILTER.check(crossing))
                .hasMessageContaining("PlantedCrossingOutsideTenancy.workTenants()");
        assertThatThrownBy(() -> TenantFilterRule.BOUNDARY_CROSSINGS_ARE_REVIEWED.check(crossing))
                .hasMessageContaining("PlantedCrossingOutsideTenancy");
    }
}
