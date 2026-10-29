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

    /**
     * The one reviewed exemption by exact class name (E16-T2 / T-149; ADR-011 Decision 1, ADR-015): the DB TARGET
     * adapter writes to a customer's external database over its own driver connection
     * ({@code DriverManager.getConnection} with the target's restricted account), never the platform datasource, so
     * no row it touches is platform tenant data and R08's tenant predicate has nothing to apply to. Not a package
     * exemption: a second class in the same package is still scanned. The exemption covers only statements on the
     * driver connection ({@link #TARGET_CONNECTION_TYPES}); a {@code DataSource}, a Spring JDBC type or a repository
     * type used in that class is still flagged.
     */
    static final String TARGET_DB_SENDER = "io.integrationplatform.routing.adapter.db.JdbcDbSender";

    /**
     * F3 (privilege probe): reads the tenant target database's own grants over the same kind of driver connection
     * (information_schema.table_privileges on the customer database, never the platform datasource).
     */
    static final String TARGET_DB_PROBE = "io.integrationplatform.routing.adapter.db.JdbcDbPrivilegeProbe";

    /** The reason, kept with the constant so a review of the exemption reads it in one place. */
    static final String TARGET_DB_SENDER_REASON = "writes to the customer's external target database over its own "
            + "driver connection, never the platform datasource (ADR-011 Decision 1; T-149)";

    /**
     * Row 586/587 release unblock: exact-class exemptions for reviewed platform-wide SQL access (neither consults tenant
     * data, so R08's tenant predicate has nothing to apply to). Unlike {@link #TARGET_DB_SENDER}, these classes run
     * against the platform datasource / Spring JDBC types and are therefore exempt as whole classes through
     * {@link #isExempt} rather than through {@link #isTargetConnectionCall}. The exemption is EXACT-CLASS AND TOP-LEVEL ONLY:
     * the constants hold fully qualified top-level names and never match a nested type. The reasons are documented with
     * each constant; a rename must not leave a dead exemption (the production scan asserts presence).
     */
    static final String AUDIT_LEDGER_LOCKDOWN_GUARD = "io.integrationplatform.app.audit.AuditLedgerLockdownGuard";
    static final String DB_BACKED_ALLOW_LIST = "io.integrationplatform.config.destination.DbBackedAllowList";

    /** 'audit-ledger role-probe startup check' (row 570 L3; reads {@code audit_event} privileges as the app login). */
    static final String AUDIT_LEDGER_LOCKDOWN_GUARD_REASON = "audit-ledger role-probe startup check";
    /** 'operator_allowed_host platform-wide config' (rows 579-580; allow-list stored in platform-wide operator config). */
    static final String DB_BACKED_ALLOW_LIST_REASON = "operator_allowed_host platform-wide config";

    static final Set<String> REVIEWED_PLATFORM_WIDE_SQL_ACCESS = Set.of(
            AUDIT_LEDGER_LOCKDOWN_GUARD, DB_BACKED_ALLOW_LIST);

    // Common aliases kept for compatibility with callers that name the set rather than the per-class constant.
    static final Set<String> PLATFORM_WIDE_SQL_ACCESS = REVIEWED_PLATFORM_WIDE_SQL_ACCESS;
    static final Set<String> PLATFORM_WIDE_REVIEWED_SQL_ACCESS = REVIEWED_PLATFORM_WIDE_SQL_ACCESS;
    static final Set<String> REVIEWED_PLATFORM_WIDE_ACCESS = REVIEWED_PLATFORM_WIDE_SQL_ACCESS;

    private static final Set<String> TARGET_CONNECTION_TYPES = Set.of("java.sql.Connection", "java.sql.Statement");

    private static final DescribedPredicate<JavaClass> REPOSITORY_BASE_OR_REVIEWED_CROSSING = DescribedPredicate.describe(
            "the tenant-scoped repository base or a reviewed @TenantBoundaryCrossing in " + CROSSING_PACKAGE,
            TenantFilterRule::isExempt);

    static final ArchRule NO_QUERY_WITHOUT_TENANT_FILTER = noQueryWithoutTenantFilterExempting(TARGET_DB_SENDER, TARGET_DB_PROBE);

    /** The rule with {@code targetSenders} as the exact classes exempt for its target-connection statements. */
    static ArchRule noQueryWithoutTenantFilterExempting(String... targetSenders) {
        DescribedPredicate<JavaAccess<?>> entryPoint = DescribedPredicate.describe(
                "a JDBC entry point (Connection, Statement, DataSource, JdbcTemplate, NamedParameterJdbcTemplate, "
                        + "JdbcClient, EntityManager query, Spring Data repository) other than " + String.join(", ", targetSenders)
                        + " on its own target connection (" + TARGET_DB_SENDER_REASON + ")",
                access -> isJdbcEntryPoint(access) && !isTargetConnectionCall(access, java.util.Set.of(targetSenders)));
        return noClasses()
                .that().resideInAPackage(ROOT_PACKAGE + "..")
                .and(not(REPOSITORY_BASE_OR_REVIEWED_CROSSING))
                .should().accessTargetWhere(entryPoint)
                .orShould().beAssignableTo("org.springframework.jdbc.core.support.JdbcDaoSupport")
                .orShould().beAssignableTo("org.springframework.data.repository.Repository")
                .because("every query must carry the tenant filter (R08): run SQL only through "
                        + "io.integrationplatform.tenancy.TenantScopedRepository");
    }

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

    private static boolean isTargetConnectionCall(JavaAccess<?> access, java.util.Set<String> targetSenders) {
        JavaClass origin = access.getOriginOwner();
        while (origin.getEnclosingClass().isPresent()) {
            origin = origin.getEnclosingClass().get();
        }
        JavaClass owner = access.getTarget().getOwner();
        return targetSenders.contains(origin.getName())
                && TARGET_CONNECTION_TYPES.stream().anyMatch(owner::isAssignableTo)
                && !owner.isAssignableTo("javax.sql.DataSource");
    }

    private static boolean isExempt(JavaClass javaClass) {
        // Platform-wide SQL access is reviewed as EXACT TOP-LEVEL CLASSES ONLY: the constants in
        // REVIEWED_PLATFORM_WIDE_SQL_ACCESS hold fully qualified top-level names; a nested type must never inherit the
        // exemption merely because it sits inside one of those classes. The top-level guard below enforces this by class
        // name alone (R08; E16-T2; row 586/587).
        JavaClass outermost = javaClass;
        while (outermost.getEnclosingClass().isPresent()) {
            outermost = outermost.getEnclosingClass().get();
        }
        return outermost.getName().equals(REPOSITORY_BASE)
                || isReviewedCrossing(outermost)
                || (javaClass.getEnclosingClass().isEmpty()
                        && REVIEWED_PLATFORM_WIDE_SQL_ACCESS.contains(javaClass.getName()));
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
