package io.integrationplatform.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/** The tenant filter rules (T-003). RED SKELETON - the predicates match nothing yet. */
final class TenantFilterRule {

    static final ArchRule NO_QUERY_WITHOUT_TENANT_FILTER = noClasses()
            .should().accessTargetWhere(DescribedPredicate.alwaysFalse())
            .allowEmptyShould(true);

    static final ArchRule BOUNDARY_CROSSINGS_ARE_REVIEWED = noClasses()
            .should().accessTargetWhere(DescribedPredicate.alwaysFalse())
            .allowEmptyShould(true);

    private TenantFilterRule() {
    }

    static JavaClasses importProductionClasses() {
        return new ClassFileImporter().importPackages("io.integrationplatform");
    }
}
