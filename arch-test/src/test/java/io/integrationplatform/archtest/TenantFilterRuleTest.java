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
 *	Given repository queries in every module that carry the tenant predicate
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

    /**
     * E14 T-056/T-060/T-062 (ADR-012 section 4): the closed register's tables are platform-wide - no row of
     * {@code scheduler.job_definition}, {@code scheduler.scheduled_tasks} or {@code scheduler.job_run} is tenant data -
     * so the scheduler's JDBC runs through the reviewed tenancy crossing ({@code SchedulerRegistry}, reason
     * "ADR-012 section 4"), never through a second JDBC call site. Red first: the E14 lane-B work (1e5c932) ran its
     * own JdbcTemplate and this scan named all three access classes.
     */
    @Test
    void theSchedulerAccessIsTheReviewedCrossingNotASecondJdbcCallSite() {
        JavaClasses production = TenantFilterRule.importProductionClasses();

        EvaluationResult result = TenantFilterRule.NO_QUERY_WITHOUT_TENANT_FILTER.evaluate(production);

        assertThat(result.getFailureReport().getDetails())
                .as("scheduler access outside the reviewed crossing")
                .noneMatch(line -> line.startsWith("Method <io.integrationplatform.config.scheduler.JdbcJobDefinitionRepository")
                        || line.startsWith("Method <io.integrationplatform.config.scheduler.JobRunSearch")
                        || line.startsWith("Method <io.integrationplatform.app.scheduler.JobOverdueDetector")
                        || line.startsWith("Method <io.integrationplatform.app.scheduler.JobIncidentJdbcSink"));
        assertThat(TenantFilterRule.REVIEWED_CROSSINGS)
                .as("the crossing's reviewed reason").contains("ADR-012 section 4");
    }

    /**
     * E16-T2 (T-149) review item for security: {@code JdbcDbSender} writes to a customer's external target database
     * over its own driver connection, never the platform datasource, so it is the one named exemption of R08's scan.
     * The exemption is exact-class and covers only the target connection's statements.
     */
    @Test
    void theTargetDbSenderIsTheOneNamedExemptionAndExistsInTheProductionScan() {
        JavaClasses production = TenantFilterRule.importProductionClasses();

        assertThat(TenantFilterRule.TARGET_DB_SENDER)
                .isEqualTo("io.integrationplatform.routing.adapter.db.JdbcDbSender");
        assertThat(production.contain(TenantFilterRule.TARGET_DB_SENDER))
                .as("the exempt class still exists (a rename must not leave a dead exemption)").isTrue();
        assertThat(production.get(TenantFilterRule.TARGET_DB_SENDER).isAssignableTo(
                "org.springframework.data.repository.Repository")).as("not a repository type").isFalse();
        assertThat(production.get(TenantFilterRule.TARGET_DB_SENDER).isAssignableTo(
                "org.springframework.jdbc.core.support.JdbcDaoSupport")).as("not a JdbcDaoSupport").isFalse();
    }

    @Test
    void theTargetDbProbeIsANamedExemptionThatExistsAndNeverTouchesThePlatformDataSource() {
        JavaClasses production = TenantFilterRule.importProductionClasses();

        assertThat(production.contain(TenantFilterRule.TARGET_DB_PROBE))
                .as("the exempt class still exists (a rename must not leave a dead exemption)").isTrue();
        assertThat(production.get(TenantFilterRule.TARGET_DB_PROBE).getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getName()))
                .as("JdbcDbPrivilegeProbe must open its own driver connection, not a platform DataSource or Spring JDBC type")
                .noneMatch(name -> name.equals("javax.sql.DataSource") || name.startsWith("org.springframework.jdbc.")
                        || name.startsWith("org.springframework.data."));
    }

    @Test
    void theTargetDbSenderNeverTouchesThePlatformDataSource() {
        JavaClasses production = TenantFilterRule.importProductionClasses();

        assertThat(production.get(TenantFilterRule.TARGET_DB_SENDER).getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getName()))
                .as("JdbcDbSender must open its own driver connection, not a platform DataSource or Spring JDBC type")
                .noneMatch(name -> name.equals("javax.sql.DataSource") || name.startsWith("org.springframework.jdbc.")
                        || name.startsWith("org.springframework.data."));
    }

    @Test
    void aDifferentClassMakingTheSameJdbcCallsIsStillFlagged() {
        JavaClasses lookalike = new ClassFileImporter().importPackages(FIXTURES + ".e16.archfixture.planted")
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("the lookalike",
                        c -> c.getSimpleName().equals("PlantedTargetDbLookalike")));
        assertThat(lookalike.size()).isEqualTo(1);

        assertThatThrownBy(() -> TenantFilterRule.NO_QUERY_WITHOUT_TENANT_FILTER.check(lookalike))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedTargetDbLookalike.insert(java.lang.String)")
                .hasMessageContaining("java.sql.Connection.prepareStatement");
    }

    @Test
    void theExemptClassStillCannotUseThePlatformDataSource() {
        JavaClasses sender = new ClassFileImporter().importPackages(FIXTURES + ".e16.archfixture.planted")
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("the datasource sender",
                        c -> c.getSimpleName().equals("PlantedTargetSenderOnPlatformDataSource")));
        assertThat(sender.size()).isEqualTo(1);
        String exempt = FIXTURES + ".e16.archfixture.planted.PlantedTargetSenderOnPlatformDataSource";

        EvaluationResult result = TenantFilterRule.noQueryWithoutTenantFilterExempting(exempt).evaluate(sender);

        assertThat(result.getFailureReport().getDetails())
                .as("DataSource.getConnection() is flagged even in the exempt class; its target-connection statements are not")
                .anyMatch(line -> line.contains("javax.sql.DataSource.getConnection"))
                .noneMatch(line -> line.contains("java.sql.Connection.prepareStatement")
                        || line.contains("java.sql.PreparedStatement.executeUpdate"));
    }

    @Test
    void reviewedBoundaryCrossingsInTenancyInternalPass() {
        // The compliant class keeps the rule's that() clause non-empty: both crossings are exempt from it.
        JavaClasses crossings = new ClassFileImporter().importPackages(
                FIXTURES + ".tenancy.internal.archfixture.crossing",
                FIXTURES + ".config.archfixture.compliant");

        assertThat(crossings.size()).isEqualTo(3);
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

    /**
     * Row 587/591 release-unblock presence check: the two reviewed platform-wide SQL-access classes are exempt as exact
     * top-level classes only, and a rename must not leave a dead exemption.
     */
    @Test
    void theReviewedPlatformWideSqlAccessClassesExistInTheProductionScan() {
        JavaClasses production = TenantFilterRule.importProductionClasses();

        assertThat(production.contain(TenantFilterRule.AUDIT_LEDGER_LOCKDOWN_GUARD))
                .as("the exempt class still exists (a rename must not leave a dead exemption)").isTrue();
        assertThat(production.contain(TenantFilterRule.DB_BACKED_ALLOW_LIST))
                .as("the exempt class still exists (a rename must not leave a dead exemption)").isTrue();
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
