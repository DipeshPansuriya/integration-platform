package io.integrationplatform.archtest;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.PackageMatcher;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tenant filter rules (T-003; R08, E1-C3; ADR-007 Decision 6 control 3).
 *
 * <p>ArchUnit cannot read the contents of an SQL string, so the rule works on call sites: no class under
 * {@code io.integrationplatform..} may call a JDBC entry point - and no class may be a repository type of its own -
 * except the tenant-scoped repository base (which adds the tenant predicate to every statement) and the reviewed
 * boundary crossings: classes in {@code io.integrationplatform.tenancy.internal..} annotated with
 * {@link TenantBoundaryCrossing} whose value is one of {@link #REVIEWED_CROSSINGS}. Class names are not pinned, so the
 * classes lane C adds are recognised by package and reason. A violation names the class and the method
 * ("Method &lt;...&gt; calls method &lt;...&gt;").
 */
final class TenantFilterRule {

    static final String ROOT_PACKAGE = "io.integrationplatform";

    static final String REPOSITORY_BASE = "io.integrationplatform.tenancy.TenantScopedRepository";

    static final String CROSSING_PACKAGE = "io.integrationplatform.tenancy.internal..";

    /** The definer functions of architecture.md section 5 (control T5), the per-transaction stamp and the
     *  e14 platform-wide scheduler registry (ADR-012 section 4: no row of the scheduler tables is tenant data). */
    static final Set<String> REVIEWED_CROSSINGS = Set.of(
            "ipaas_login_bootstrap",
            "ipaas_client_bootstrap",
            "ipaas_work_tenant",
            "ipaas_active_tenants",
            "ADR-007 Decision 6",
            "ADR-012 section 4",
            "T6");

    /** Types whose every method runs or prepares SQL. */
    private static final List<String> SQL_TYPES = List.of(
            "org.springframework.jdbc.core.JdbcOperations",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
            "org.springframework.jdbc.core.simple.JdbcClient",
            "org.springframework.jdbc.core.simple.SimpleJdbcInsertOperations",
            "org.springframework.jdbc.core.simple.SimpleJdbcCallOperations",
            "org.springframework.data.repository.Repository");

    /** The fluent statement specs of JdbcClient (nested types). */
    private static final String JDBC_CLIENT_NESTED = "org.springframework.jdbc.core.simple.JdbcClient$";

    /** Types where only the named methods are entry points (a RowMapper may still read a ResultSet). */
    private static final Map<String, Set<String>> SQL_METHODS = Map.of(
            "java.sql.Connection", Set.of("prepareStatement", "prepareCall", "createStatement", "nativeSQL"),
            "javax.sql.DataSource", Set.of("getConnection"),
            "jakarta.persistence.EntityManager", Set.of("createNativeQuery", "createQuery",
                    "createNamedQuery", "createStoredProcedureQuery", "createNamedStoredProcedureQuery"));

    private static final String JDBC_STATEMENT = "java.sql.Statement";

    private static final DescribedPredicate<JavaAccess<?>> JDBC_ENTRY_POINT = DescribedPredicate.describe(
            "a JDBC entry point (Connection, Statement, DataSource, JdbcTemplate, NamedParameterJdbcTemplate, "
                    + "JdbcClient, EntityManager query, Spring Data repository)",
            TenantFilterRule::isJdbcEntryPoint);

    private static final DescribedPredicate<JavaClass> REPOSITORY_BASE_OR_REVIEWED_CROSSING = DescribedPredicate.describe(
            "the tenant-scoped repository base or a reviewed @TenantBoundaryCrossing in " + CROSSING_PACKAGE,
            TenantFilterRule::isExempt);

    static final ArchRule NO_QUERY_WITHOUT_TENANT_FILTER = noClasses()
            .that().resideInAPackage(ROOT_PACKAGE + "..")
            .and(not(REPOSITORY_BASE_OR_REVIEWED_CROSSING))
            .should().accessTargetWhere(JDBC_ENTRY_POINT)
            .orShould().beAssignableTo("org.springframework.jdbc.core.support.JdbcDaoSupport")
            .orShould().beAssignableTo("org.springframework.data.repository.Repository")
            .because("every query must carry the tenant filter (R08): run SQL only through "
                    + "io.integrationplatform.tenancy.TenantScopedRepository");

    static final ArchRule BOUNDARY_CROSSINGS_ARE_REVIEWED = classes()
            .that().areAnnotatedWith(TenantBoundaryCrossing.class)
            .should().resideInAPackage(CROSSING_PACKAGE)
            .andShould(nameAReviewedReason())
            .allowEmptyShould(true)
            .because("a tenant boundary crossing is reviewed (architecture.md section 5, control T5; "
                    + "ADR-007 Decision 6)");

    private TenantFilterRule() {
    }

    /** Every module's production classes; test classes (and so the planted fixtures) are excluded. */
    static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT_PACKAGE);
    }

    private static boolean isJdbcEntryPoint(JavaAccess<?> access) {
        JavaClass owner = access.getTarget().getOwner();
        String method = access.getTarget().getName();
        if (owner.getName().startsWith(JDBC_CLIENT_NESTED) || SQL_TYPES.stream().anyMatch(owner::isAssignableTo)) {
            return true;
        }
        if (owner.isAssignableTo(JDBC_STATEMENT)) {
            return method.startsWith("execute") || method.equals("addBatch");
        }
        return SQL_METHODS.entrySet().stream()
                .anyMatch(entry -> owner.isAssignableTo(entry.getKey()) && entry.getValue().contains(method));
    }

    private static boolean isExempt(JavaClass javaClass) {
        JavaClass outermost = javaClass;
        while (outermost.getEnclosingClass().isPresent()) {
            outermost = outermost.getEnclosingClass().get();
        }
        return outermost.getName().equals(REPOSITORY_BASE) || isReviewedCrossing(outermost);
    }

    private static boolean isReviewedCrossing(JavaClass javaClass) {
        return PackageMatcher.of(CROSSING_PACKAGE).matches(javaClass.getPackageName())
                && javaClass.tryGetAnnotationOfType(TenantBoundaryCrossing.class)
                        .map(crossing -> REVIEWED_CROSSINGS.contains(crossing.value()))
                        .orElse(false);
    }

    private static ArchCondition<JavaClass> nameAReviewedReason() {
        return new ArchCondition<>("name a reviewed reason " + REVIEWED_CROSSINGS) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String reason = javaClass.getAnnotationOfType(TenantBoundaryCrossing.class).value();
                if (!REVIEWED_CROSSINGS.contains(reason)) {
                    events.add(SimpleConditionEvent.violated(javaClass, "Class <" + javaClass.getName()
                            + "> crosses the tenant boundary for an unreviewed reason '" + reason + "'"));
                }
            }
        };
    }
}
