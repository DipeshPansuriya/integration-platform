package io.integrationplatform.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The transform guards (E7-C8; architecture.md:51, row {@code platform-transform}): transform is the mapping
 * execution engine - the JSONata language through Dashjoin's Java port called directly (ADR-006) - so
 * <ul>
 * <li>no class under {@code io.integrationplatform.transform..} imports {@code org.apache.camel..}: the only Camel
 * module is platform-routing (R12, ADR-010 section 3); sanjay-developer flagged the wiring-level gap this rule
 * closes;</li>
 * <li>no transform class imports config's mapping internals ({@code config/internal..}, {@code config/mapping..}):
 * platform-config owns mappings and their immutable versions (architecture.md:51 row platform-config); the engine
 * sees them only through its own port types - it never reaches back into the module that publishes them.
 * </ul>
 *
 * <p>Same conventions as {@link TenantFilterRule}: the production run imports every module's main classes (test
 * fixtures excluded); the planted fixtures prove the rules bite.
 */
final class TransformNoCamelRule {

    static final String TRANSFORM_PACKAGE = "io.integrationplatform.transform..";

    static final String CAMEL_PACKAGE = "org.apache.camel..";

    static final String CONFIG_INTERNAL_PACKAGE = "io.integrationplatform.config.internal..";

    static final String CONFIG_MAPPING_PACKAGE = "io.integrationplatform.config.mapping..";

    static final ArchRule TRANSFORM_NEVER_IMPORTS_CAMEL = noClasses()
            .that().resideInAPackage(TRANSFORM_PACKAGE)
            .should().dependOnClassesThat().resideInAPackage(CAMEL_PACKAGE)
            .because("transform is the engine-only module: the only Camel module is platform-routing "
                    + "(E7-C8; R12, ADR-010 section 3; architecture.md:51 row platform-transform 'Must not: Use Camel')");

    static final ArchRule TRANSFORM_IMPORTS_NO_CONFIG_MAPPING_INTERNALS = noClasses()
            .that().resideInAPackage(TRANSFORM_PACKAGE)
            .should().dependOnClassesThat().resideInAPackage(CONFIG_INTERNAL_PACKAGE)
            .orShould().dependOnClassesThat().resideInAPackage(CONFIG_MAPPING_PACKAGE)
            .because("platform-config owns mappings and their immutable versions (ADR-013); the engine consumes "
                    + "mapped versions only through its port types, never config's internals "
                    + "(architecture.md:51 direction, sanjay-developer)");

    private TransformNoCamelRule() {
    }

    /** Every module's production classes; test classes (and so the planted fixtures) are excluded. */
    static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.integrationplatform");
    }
}