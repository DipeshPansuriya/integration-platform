package io.integrationplatform.tenancy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one way a module adapter runs SQL against a tenant-scoped table (T-004; R08, R09, R11; ADR-007 Decision 6). The
 * ArchUnit rule of arch-test (T-003) refuses any other JDBC call site outside the reviewed boundary crossings.
 *
 * <p>Every statement gets the predicate {@value #TENANT_PREDICATE} - the caller's own condition is ANDed after it in
 * parentheses, so it can narrow the rows but never widen them past the tenant. The tenant comes only from
 * {@link TenantContext#requireTenantId()}; a caller-supplied {@value #TENANT_PARAMETER} parameter is refused. The tenant
 * id is bound as a string and cast explicitly with {@code ::uuid} (R09). Before any statement is created the access is
 * refused when no tenant is bound ({@link NoTenantBoundException}) or when no transaction is active (R11 - the tenant
 * setting of row-level security is stamped per transaction).
 *
 * <p>Callers pass SQL fragments that are compile-time constants and every value as a named parameter (R11: no SQL built
 * from non-constant strings). The filtered table's {@code tenant_id} column must be unambiguous in the statement.
 */
public abstract class TenantScopedRepository {

    /** The named parameter that carries the bound tenant; reserved - a caller may not supply it. */
    public static final String TENANT_PARAMETER = "tenant_id";

    /** The predicate added to every statement, with the explicit uuid cast of R09. */
    public static final String TENANT_PREDICATE = "tenant_id = :" + TENANT_PARAMETER + "::uuid";

    private final JdbcClient jdbcClient;
    private final TenantContext tenantContext;

    protected TenantScopedRepository(JdbcClient jdbcClient, TenantContext tenantContext) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
    }

    /**
     * Runs {@code selectFrom WHERE tenant predicate [AND (condition)]} for the bound tenant.
     *
     * @param selectFrom the {@code SELECT ... FROM <table>} part, with no WHERE clause (a constant)
     * @param condition an extra condition, or {@code null} for none (a constant; values as named parameters)
     */
    protected final <T> List<T> query(String selectFrom, String condition, Map<String, ?> parameters,
            RowMapper<T> rowMapper) {
        Map<String, Object> bound = bindTenant(parameters);
        return jdbcClient.sql(scoped(selectFrom, condition)).params(bound).query(rowMapper).list();
    }

    /**
     * Runs {@code updateOrDelete WHERE tenant predicate [AND (condition)]} for the bound tenant.
     *
     * @param updateOrDelete the {@code UPDATE <table> SET ...} or {@code DELETE FROM <table>} part, with no WHERE clause
     * @param condition an extra condition, or {@code null} for none
     * @return the number of rows changed - only rows of the bound tenant can change
     */
    protected final int update(String updateOrDelete, String condition, Map<String, ?> parameters) {
        Map<String, Object> bound = bindTenant(parameters);
        return jdbcClient.sql(scoped(updateOrDelete, condition)).params(bound).update();
    }

    private Map<String, Object> bindTenant(Map<String, ?> parameters) {
        if (parameters.containsKey(TENANT_PARAMETER)) {
            throw new IllegalArgumentException("The '" + TENANT_PARAMETER
                    + "' parameter is reserved: the tenant comes only from the TenantContext");
        }
        UUID tenantId = tenantContext.requireTenantId();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A tenant-scoped access must run inside a transaction (R11)");
        }
        Map<String, Object> bound = new LinkedHashMap<>(parameters);
        bound.put(TENANT_PARAMETER, tenantId.toString());
        return bound;
    }

    /**
     * Like {@link #query} but with a trailing constant ORDER BY and a hard row limit. The ordering and limit are supplied
     * by the caller as compile-time constants; they are appended after the tenant predicate so row-level security and
     * the bound tenant gate still apply (the base query is unchanged for those keys).
     */
    protected final <T> List<T> query(String selectFrom, String condition, Map<String, ?> parameters,
            RowMapper<T> rowMapper, String orderBy, int limit) {
        Map<String, Object> bound = bindTenant(parameters);
        String sql = scoped(selectFrom, condition) + " ORDER BY " + Objects.toString(orderBy) + " LIMIT :__limit";
        bound.put("__limit", limit);
        return jdbcClient.sql(sql).params(bound).query(rowMapper).list();
    }

    private static String scoped(String statement, String condition) {
        String scoped = statement + " WHERE " + TENANT_PREDICATE;
        return condition == null || condition.isBlank() ? scoped : scoped + " AND (" + condition + ")";
    }
}
