package io.integrationplatform.archtest;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import java.util.List;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * E14 T-056 (ADR-012 section 3): one mechanism runs all background work - the job register on db-scheduler. The
 * arch rules refuse any second mechanism in production code: no Spring {@code @Scheduled} method, no
 * {@code @EnableScheduling} configuration, and no in-memory timer ({@code ScheduledExecutorService}, {@code Timer},
 * {@code TimerTask}/{@code ScheduledThreadPoolExecutor}) anywhere in {@code io.integrationplatform..}. The planted
 * fixture in this module's test tree ({@code ..archfixture.planted.PlantedSpringScheduledTimer}) carries exactly
 * what is refused and is never in the production scan (DO_NOT_INCLUDE_TESTS, the TenantFilterRule pattern).
 */
final class SchedulerTimingRules {

    private static boolean isAnInMemoryTimerType(JavaClass javaClass) {
        return List.of(
                "java.util.concurrent.ScheduledExecutorService",
                "java.util.concurrent.ScheduledThreadPoolExecutor",
                "java.util.Timer",
                "java.util.TimerTask").contains(javaClass.getName());
    }

    private static final DescribedPredicate<JavaClass> IN_MEMORY_TIMER_TYPES = DescribedPredicate.describe(
            "in-memory timer types (ScheduledExecutorService, ScheduledThreadPoolExecutor, Timer, TimerTask)",
            SchedulerTimingRules::isAnInMemoryTimerType);

    /** Every io.integrationplatform.. class carries no @Scheduled method and no @EnableScheduling annotation. */
    static final ArchRule NO_SPRING_TIMER_ANNOTATIONS = classes()
            .that().resideInAPackage("io.integrationplatform..")
            .should(notBeTimedBySpringAnnotations());

    static final ArchRule NO_IN_MEMORY_TIMER = noClasses()
            .that().resideInAPackage("io.integrationplatform..")
            .should().dependOnClassesThat(IN_MEMORY_TIMER_TYPES);

    private SchedulerTimingRules() {
    }

    static JavaClasses importProductionClasses() {
        // every module's production classes; the archtest and fixture trees are test-only and never reach it
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.integrationplatform");
    }

    static JavaClasses importPlantedFixtures() {
        return new ClassFileImporter().importPackages(
                "io.integrationplatform.scheduler.archfixture.planted");
    }

    static JavaClasses importCompliantFixtures() {
        return new ClassFileImporter().importPackages(
                "io.integrationplatform.scheduler.archfixture.compliant");
    }

    private static ArchCondition<JavaClass> notBeTimedBySpringAnnotations() {
        return new ArchCondition<>("carry no @Scheduled method and no @EnableScheduling annotation") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (javaClass.isAnnotatedWith(EnableScheduling.class)) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getName() + " is annotated with @EnableScheduling; db-scheduler owns timing "
                                    + "(ADR-012 section 3)"));
                }
                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.isAnnotatedWith(Scheduled.class)) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                                javaClass.getName() + "." + method.getName() + " is annotated with @Scheduled; "
                                        + "db-scheduler owns timing (ADR-012 section 3)"));
                    }
                }
            }
        };
    }
}