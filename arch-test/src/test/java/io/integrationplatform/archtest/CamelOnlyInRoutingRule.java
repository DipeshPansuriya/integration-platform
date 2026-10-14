package io.integrationplatform.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * "org.apache.camel only in platform-routing" (T-121, E9-F1-S2 @negative; R12, ADR-010; E9-C6).
 *
 * <p>The routing core is the one approved Camel component: no class under {@code io.integrationplatform..}
 * outside {@code io.integrationplatform.routing..} may depend on {@code org.apache.camel..}. The rule fails
 * the build and names the offending class, the same red-green pattern the tenant filter rule (T-003) uses -
 * a planted violation in a fixture must fail ONLY against the deliberate violation, never against any module's
 * compliant production classes.
 *
 * <p>Camel itself may be absent from the classpath of a module that (wrongly) references it - the dependency is
 * a bytecode reference by name, so the rule sees it as soon as the jar arrives on the scope's classpath.
 */
final class CamelOnlyInRoutingRule {

    /** Every module's production classes (ADR-001 item 2, R08). */
    static final String ROOT_PACKAGE = "io.integrationplatform";

    /** The one module the routing core owns (R12: Camel lives only in platform-routing). */
    static final String ROUTING_PACKAGE = "io.integrationplatform.routing..";

    /** Camel root package: every Camel artefact is org.apache.camel.* (starters included). */
    static final String CAMEL_PACKAGE = "org.apache.camel..";

    static final ArchRule CAMEL_ONLY_IN_ROUTING = noClasses()
            .that().resideInAPackage(ROOT_PACKAGE + "..")
            .and().resideOutsideOfPackages(ROUTING_PACKAGE)
            .should().dependOnClassesThat().resideInAnyPackage(CAMEL_PACKAGE)
            .because("the routing core is the only approved Camel component (R12, ADR-010 section 1; E9-C6):"
                    + " org.apache.camel may be imported only under " + ROUTING_PACKAGE);

    private CamelOnlyInRoutingRule() {
    }

    /** Every module's production classes; test classes (and so the planted fixtures) are excluded. */
    static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT_PACKAGE);
    }
}