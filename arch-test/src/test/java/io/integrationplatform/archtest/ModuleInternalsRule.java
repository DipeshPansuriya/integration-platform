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

/**
 * No module imports another module's {@code .internal} package (row 278 E7 B2 follow-up; found by mahesh-architect):
 * a module is the third package segment ({@code io.integrationplatform.<module>..}, one root per Maven module) and
 * its {@code internal} packages are its own - every other module sees it only through its {@code api} / {@code port}
 * types. The case that made it a rule: platform-config's mapping-test wiring constructed transform's
 * {@code internal.DashjoinJsonataTransformPort} directly. {@link TransformNoCamelRule} already guards the other
 * direction (transform -> config internals); this rule covers every pair.
 *
 * <p>Same conventions as {@link TenantFilterRule}: the production run imports every module's main classes (test
 * fixtures excluded); the planted fixture proves the rule bites.
 */
final class ModuleInternalsRule {

    static final String ROOT_PACKAGE = "io.integrationplatform";

    static final ArchRule NO_MODULE_IMPORTS_ANOTHER_MODULES_INTERNALS = classes()
            .that().resideInAPackage(ROOT_PACKAGE + "..")
            .should(notDependOnAnotherModulesInternals())
            .because("a module's internal packages are its own; other modules use its api/port types only "
                    + "(ADR-006 rule 1 for transform; architecture.md module table)");

    private ModuleInternalsRule() {
    }

    /** Every module's production classes; test classes (and so the planted fixtures) are excluded. */
    static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT_PACKAGE);
    }

    /** The module segment of a package under the root, or "" outside it. */
    static String moduleOf(String packageName) {
        if (!packageName.startsWith(ROOT_PACKAGE + ".")) {
            return "";
        }
        String rest = packageName.substring(ROOT_PACKAGE.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    static boolean isInternal(String packageName) {
        return (packageName + ".").contains(".internal.");
    }

    private static ArchCondition<JavaClass> notDependOnAnotherModulesInternals() {
        return new ArchCondition<>("not depend on another module's internal package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String own = moduleOf(origin.getPackageName());
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getBaseComponentType().getPackageName();
                    String targetModule = moduleOf(target);
                    if (!targetModule.isEmpty() && !targetModule.equals(own) && isInternal(target)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }
}
