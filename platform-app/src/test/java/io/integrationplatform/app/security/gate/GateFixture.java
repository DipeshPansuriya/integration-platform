package io.integrationplatform.app.security.gate;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.delivery.port.Stage1Delivery;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T141 gate helper for the container cases that run on the real schema as the restricted application login, and
 * through the application's own tenant-stamping transaction manager (the worker path: a tenant bound on the thread,
 * stamped transaction-locally by the production manager). Test code only.
 */
final class GateFixture {

    static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");

    /** A worker-style tenant binding; the application's WorkerAwareTenantContext composes every TenantContext bean. */
    static final class BoundTenant implements TenantContext {

        private final ThreadLocal<UUID> bound = new ThreadLocal<>();
        private final TenantContext request;

        /** {@code request} answers on threads with no worker binding (the web request path stays as shipped). */
        BoundTenant(TenantContext request) {
            this.request = request;
        }

        @Override
        public Optional<UUID> currentTenantId() {
            UUID worker = bound.get();
            return worker != null ? Optional.of(worker) : request.currentTenantId();
        }

        <T> T callAs(UUID tenant, Supplier<T> work) {
            bound.set(tenant);
            try {
                return work.get();
            } finally {
                bound.remove();
            }
        }
    }

    @FunctionalInterface
    interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final PostgreSQLContainer postgres;
    private final PlatformTestWiring.RestrictedLogin login;
    private final BoundTenant binding;
    private final PlatformTransactionManager transactions;

    GateFixture(PostgreSQLContainer postgres, PlatformTestWiring.RestrictedLogin login, BoundTenant binding,
            PlatformTransactionManager transactions) {
        this.postgres = postgres;
        this.login = login;
        this.binding = binding;
        this.transactions = transactions;
    }

    /** Both gate tenants, as the migration owner (the tenant directory is not an application-role write). */
    void seedTenants() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(postgres); Statement sql = owner.createStatement()) {
            sql.execute("insert into public.tenant (id, code, status) values ('" + TENANT_A + "', 'gate-a',"
                    + " 'ACTIVE'), ('" + TENANT_B + "', 'gate-b', 'ACTIVE') on conflict do nothing");
        }
    }

    /** The production path: tenant bound on this thread, one transaction through the stamping manager. */
    <T> T inTenantTx(UUID tenant, Supplier<T> work) {
        return binding.callAs(tenant, () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    /** Raw JDBC as the restricted application login, one transaction stamped with {@code tenant}. */
    <T> T asTenant(UUID tenant, SqlWork<T> work) throws SQLException {
        try (Connection app = DriverManager.getConnection(postgres.getJdbcUrl(), login.username(),
                login.password())) {
            app.setAutoCommit(false);
            try (PreparedStatement stamp = app.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                stamp.setString(1, tenant.toString());
                stamp.execute();
            }
            T result = work.run(app);
            app.commit();
            return result;
        }
    }

    UUID insertMessage(UUID tenant, Instant acceptedAt, String businessKey) throws SQLException {
        return asTenant(tenant, c -> {
            try (PreparedStatement insert = c.prepareStatement("insert into public.message (tenant_id, interface_id,"
                    + " interface_version_id, source_contract_version_id, business_key, accepted_at) values (?::uuid,"
                    + " gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), ?, ?) returning id")) {
                insert.setString(1, tenant.toString());
                insert.setString(2, businessKey);
                insert.setTimestamp(3, Timestamp.from(acceptedAt));
                try (ResultSet rs = insert.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, UUID.class);
                }
            }
        });
    }

    /** A single value read as {@code tenant} (RLS applies); null when no row is visible. */
    String scalar(UUID tenant, String sql, Object... params) throws SQLException {
        return asTenant(tenant, c -> scalarOn(c, sql, params));
    }

    String ownerScalar(String sql, Object... params) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(postgres)) {
            return scalarOn(owner, sql, params);
        }
    }

    void ownerExecute(String sql, Object... params) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(postgres);
                PreparedStatement st = owner.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                st.setObject(i + 1, params[i]);
            }
            st.execute();
        }
    }

    private static String scalarOn(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                st.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static Stage1Delivery delivery(UUID messageId, UUID routeId, Instant acceptedAt) {
        return new Stage1Delivery(messageId, routeId, acceptedAt, null, UUID.randomUUID(), UUID.randomUUID(), "gate|K",
                UUID.randomUUID(), "K", 1L);
    }

    /** The SQLSTATE at the bottom of a (possibly Spring-translated) failure. */
    static String sqlState(Throwable failure) {
        String state = null;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException e) {
                state = e.getSQLState();
            }
        }
        return state;
    }
}
