package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Stamps the tenant setting at the start of every transaction (ADR-007 Decision 6, option A; rule R11).
 *
 * <ol>
 *   <li>Rule 1: {@code set_config('app.tenant_id', <bound tenant>, true)} - transaction-local, parameterised, from
 *       {@link TenantContext} only. Never at session scope, so a pooled connection carries nothing into the next
 *       transaction (rule 4).</li>
 *   <li>Rule 2: nothing is stamped outside a transaction, and nothing when no tenant is bound - the setting stays
 *       empty and every tenant table fails closed (ADR-009 Decision 5). That is also what lets the login bootstrap run
 *       before binding.</li>
 * </ol>
 *
 * A failed stamp fails the transaction start; the connection is released by the base class.
 */
@TenantBoundaryCrossing("ADR-007 Decision 6")
class TenantStampingTransactionManager extends JdbcTransactionManager {

    static final String STAMP_SQL = "select set_config('app.tenant_id', ?, true)";

    private final transient TenantContext tenantContext;

    TenantStampingTransactionManager(DataSource dataSource, TenantContext tenantContext) {
        super(dataSource);
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
    }

    @Override
    protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(connection, definition);
        Optional<UUID> tenant = tenantContext.currentTenantId();
        if (tenant.isEmpty()) {
            return;
        }
        try (PreparedStatement stamp = connection.prepareStatement(STAMP_SQL)) {
            stamp.setString(1, tenant.get().toString());
            stamp.execute();
        }
    }
}
