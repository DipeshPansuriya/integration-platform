package io.integrationplatform.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

/**
 * Routing never reaches into config's application or adapter layers (row 278 C2, ADR-017 Decision 6 - the first
 * enforced step of ADR-015 D2): a class in {@code io.integrationplatform.routing..} must not depend on any
 * {@code io.integrationplatform.config..} package whose name contains {@code .application} or {@code .adapter}.
 * The case that made it a rule: {@code RouteBuildRefusedException} extended config's
 * {@code application.ConfigApiException}, so config's HTTP seam leaked into routing.
 *
 * <p>Exactly one named exception, which predates ADR-017 and is listed in its Open section:
 * {@code config.flow.application.FlowBundle} ({@code Stage1FanOut}); its nested types are part of it. The full
 * api-only allowlist stays with the E10 seam (ADR-015 D2).
 *
 * <p>Same conventions as {@link ModuleInternalsRule}: the production run imports every module's main classes (test
 * fixtures excluded); the planted fixture proves the rule bites.
 */
final class RoutingConfigBoundaryRule {

    static final String ROUTING = "io.integrationplatform.routing";
    static final String CONFIG = "io.integrationplatform.config";

    /** The one named exception (ADR-017 Decision 6 / Open). */
    static final Set<String> NAMED_EXCEPTIONS = Set.of("io.integrationplatform.config.flow.application.FlowBundle");

    static final ArchRule ROUTING_USES_NO_CONFIG_APPLICATION_OR_ADAPTER = classes()
            .that().resideInAPackage(ROUTING + "..")
            .should(notDependOnConfigApplicationOrAdapter())
            .because("config's application and adapter layers are its own; routing uses config's api types only "
                    + "(ADR-017 Decision 6, ADR-015 D2)");

    private RoutingConfigBoundaryRule() {
    }

    /** Every module's production classes; test classes (and so the planted fixtures) are excluded. */
    static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ModuleInternalsRule.ROOT_PACKAGE);
    }

    /** A config package of the application or adapter layer. */
    static boolean isConfigApplicationOrAdapter(String packageName) {
        return (packageName.equals(CONFIG) || packageName.startsWith(CONFIG + "."))
                && (packageName.contains(".application") || packageName.contains(".adapter"));
    }

    /** The named exception, or one of its nested types. */
    static boolean isNamedException(String className) {
        return NAMED_EXCEPTIONS.stream().anyMatch(named -> className.equals(named) || className.startsWith(named + "$"));
    }

    private static ArchCondition<JavaClass> notDependOnConfigApplicationOrAdapter() {
        return new ArchCondition<>("not depend on a config .application or .adapter package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    if (isConfigApplicationOrAdapter(target.getPackageName()) && !isNamedException(target.getName())) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }
}
