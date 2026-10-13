package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;

import org.junit.jupiter.api.Test;

/**
 * E14 T-056 (ADR-012 section 3): <b>no {@code @Scheduled}, {@code @EnableScheduling} or in-memory timer anywhere in
 * {@code io.integrationplatform..}</b> - one mechanism runs all background work (db-scheduler; a violation fails the
 * build).
 *
 * <pre>
 * &#64;negative Scenario: An annotation-driven or in-memory timer fails the build
 *   Given a Spring {@code @Scheduled} method, an {@code @EnableScheduling} configuration or a
 *         {@code ScheduledExecutorService} in any {@code io.integrationplatform..} class
 *   When the build runs the ArchUnit rule
 *   Then the build fails and names the class
 * </pre>
 *
 * <p>The production run imports every module's production classes (tests excluded, so the planted fixtures of this
 * module never reach it - the TenantFilterRuleTest pattern). The fixture runs check the SAME rules against the
 * planted packages and assert they fail.
 */
class SchedulerTimingRulesTest {

    // Scenario: production code passes - nothing in any module may carry a timer.
    @Test
    void productionClassesOfEveryModuleCarryNoTimer() {
        JavaClasses production = SchedulerTimingRules.importProductionClasses();

        SchedulerTimingRules.NO_SPRING_TIMER_ANNOTATIONS.check(production);
        SchedulerTimingRules.NO_IN_MEMORY_TIMER.check(production);
    }

    @Test
    void productionScanCoversEveryModuleAndNoFixture() {
        JavaClasses production = SchedulerTimingRules.importProductionClasses();

        assertThat(production.contain("io.integrationplatform.IntegrationPlatformApplication"))
                .as("platform-app in scope").isTrue();
        assertThat(production.containPackage("io.integrationplatform.config"))
                .as("platform-config in scope").isTrue();
        assertThat(production.containPackage("io.integrationplatform.delivery"))
                .as("platform-delivery in scope").isTrue();
        assertThat(production.containPackage("io.integrationplatform.routing"))
                .as("platform-routing in scope").isTrue();
        assertThat(production.stream().map(c -> c.getName()))
                .as("no planted fixture or test class in the production scan")
                .noneMatch(name -> name.contains(".archfixture.") || name.startsWith("io.integrationplatform.archtest."));
    }

    // Scenario: violations fail - the planted timer is caught and the class is named.
    @Test
    void aPlantedSpringScheduledTimerFailsBothRules() {
        JavaClasses planted = SchedulerTimingRules.importPlantedFixtures();

        assertThat(planted.contain("io.integrationplatform.scheduler.archfixture.planted.PlantedSpringScheduledTimer"))
                .as("the planted timer is in scope").isTrue();

        assertThatThrownBy(() -> SchedulerTimingRules.NO_SPRING_TIMER_ANNOTATIONS.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedSpringScheduledTimer");
        assertThatThrownBy(() -> SchedulerTimingRules.NO_IN_MEMORY_TIMER.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedSpringScheduledTimer");
    }

    // The compliant shape passes: a job body behind the JobHandler SPI, no timing of its own.
    @Test
    void aSchedulerDrivenJobBodyPasses() {
        JavaClasses compliant = SchedulerTimingRules.importCompliantFixtures();

        assertThat(compliant.contain("io.integrationplatform.scheduler.archfixture.compliant.CompliantSchedulerDrivenJob"))
                .as("the compliant job body is in scope").isTrue();
        SchedulerTimingRules.NO_SPRING_TIMER_ANNOTATIONS.check(compliant);
        SchedulerTimingRules.NO_IN_MEMORY_TIMER.check(compliant);
    }
}