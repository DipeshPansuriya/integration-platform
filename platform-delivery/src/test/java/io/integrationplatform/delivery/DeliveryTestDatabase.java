package io.integrationplatform.delivery;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lane C container fixture (T-012, T-013, T-091, T-193): one PostgreSQL per test JVM migrated with every module's
 * migrations (tenancy V1_3_*, outbox V2_0_1, lane C V2_3_*), set up the way a deployment is. The roles mirror
 * lane C's E1 fixture: an owner login that Flyway runs as and that does the partition maintenance (T6: never the
 * application role), and the restricted {@code ipaas_app} login whose grants are what the append-only and
 * no-DDL proofs exercise. Passwords are random per run and live only in memory (R07).
 */
public final class DeliveryTestDatabase {

    public static final String OWNER = "ipaas_migrator";
    public static final String APP_LOGIN = "ipaas_app_login";
    public static final String APP_ROLE = "ipaas_app";

    public static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    public static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");

    /** An interface id for the ordering scope (no interface table before E3; the column is a plain uuid). */
    public static final UUID INTERFACE_ID = UUID.fromString("11111111-0000-4000-8000-000000000001");
    public static final UUID TARGET_ID = UUID.fromString("22222222-0000-4000-8000-000000000002");

    private static DeliveryTestDatabase instance;
    private static RuntimeException setupFailure;

    private final org.testcontainers.postgresql.PostgreSQLContainer container =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.11-alpine");
    private final String ownerPassword = randomPassword();
    private final String appPassword = randomPassword();

    private DeliveryTestDatabase() {
    }

    public static synchronized DeliveryTestDatabase get() {
        if (setupFailure != null) {
            throw setupFailure;
        }
        if (instance == null) {
            DeliveryTestDatabase database = new DeliveryTestDatabase();
            try {
                database.container.start();
                database.setUp();
            } catch (Exception e) {
                setupFailure = new IllegalStateException("lane C delivery database setup failed: " + e.getMessage(),
                        e);
                throw setupFailure;
            }
            instance = database;
        }
        return instance;
    }

    private void setUp() throws SQLException {
        try (Connection admin = superuser(); Statement sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + OWNER + " LOGIN CREATEROLE PASSWORD '" + ownerPassword + "'");
            sql.execute("ALTER DATABASE " + container.getDatabaseName() + " OWNER TO " + OWNER);
        }
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), OWNER, ownerPassword)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection admin = superuser(); Statement sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + APP_LOGIN + " LOGIN PASSWORD '" + appPassword + "' IN ROLE " + APP_ROLE);
        }
        try (Connection owner = owner(); Statement sql = owner.createStatement()) {
            sql.execute("insert into public.tenant (id, code, status, time_zone) values"
                    + " ('" + TENANT_A + "', 'tenant-a', 'ACTIVE', 'Etc/UTC'),"
                    + " ('" + TENANT_B + "', 'tenant-b', 'ACTIVE', 'Etc/UTC')");
        }
    }

    /** A pool as the restricted application login; {@code size} must cover the widest concurrency test. */
    public HikariDataSource appPool(int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl());
        config.setUsername(APP_LOGIN);
        config.setPassword(appPassword);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);
        config.setPoolName("lane-c-delivery-pool");
        return new HikariDataSource(config);
    }

    /** The application pool with the JVM-default size of 4. */
    public HikariDataSource appPool() {
        return appPool(4);
    }

    public Connection superuser() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
                container.getPassword());
    }

    public Connection owner() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), OWNER, ownerPassword);
    }

    /** The DDL identity that is never the application role (T6): partition creation and the purge drop. */
    public void asOwner(String... statements) throws SQLException {
        try (Connection owner = owner(); Statement sql = owner.createStatement()) {
            for (String statement : statements) {
                sql.execute(statement);
            }
        }
    }

    public String dayPartitionName(String table, String isoDate) {
        return table + "_" + isoDate.replace("-", "");
    }

    /**
     * Creates a day partition of {@code table} for [isoDate, isoDate + 1 day) as the owner; no-op when present.
     * Every maintenance-made child - migration-predated or created here - carries the parent's
     * {@code ipaas_app} DML grants: PostgreSQL routes parent-scoped DML with only the parent's grant, but a
     * partition-level repair or a direct-day lock (the purge-isolation proof) must be runnable without an
     * escalation of the role's rights (T6). Idempotent: the grants re-apply to a partition that already exists.
     */
    public void createDayPartition(String table, String isoDate) throws SQLException {
        String name = dayPartitionName(table, isoDate);
        boolean exists;
        try (Connection owner = owner(); PreparedStatement check = owner.prepareStatement(
                "select exists (select 1 from pg_class where relname = ?)")) {
            check.setString(1, name);
            exists = check.executeQuery().next() && check.getResultSet().getBoolean(1);
        }
        if (!exists) {
            asOwner("create table public." + name + " partition of public." + table
                    + " for values from ('" + isoDate + "'::date) to (('" + isoDate + "'::date) + interval '1 day')");
        }
        try (Connection owner = owner(); PreparedStatement grants = owner.prepareStatement(
                "select privilege_type from information_schema.role_table_grants"
                        + " where grantee = ? and table_schema = 'public' and table_name = ?")) {
            grants.setString(1, APP_ROLE);
            grants.setString(2, table);
            try (var privileges = grants.executeQuery(); Statement sql = owner.createStatement()) {
                while (privileges.next()) {
                    sql.execute("grant " + privileges.getString(1) + " on public." + name + " to " + APP_ROLE);
                }
            }
        }
    }

    /** A JdbcClient over its own pool for {@code dataSource}, plus the transaction and tenant helpers it needs. */
    public static DeliveryTx tx(DataSource dataSource) {
        return new DeliveryTx(dataSource);
    }

    /** A {@link TenantContext} fixed to one tenant (tests supply their own implementation, seam javadoc). */
    public static TenantContext tenant(UUID tenantId) {
        return () -> Optional.of(tenantId);
    }

    /** Test wiring: the thread-bound transaction of Spring JDBC plus the per-transaction tenant stamp. */
    public static final class DeliveryTx {

        private final DataSource dataSource;
        private final DataSourceTransactionManager txManager;
        private final TransactionTemplate transactionTemplate;
        private final JdbcClient jdbcClient;

        DeliveryTx(DataSource dataSource) {
            this.dataSource = dataSource;
            this.txManager = new DataSourceTransactionManager(dataSource);
            this.transactionTemplate = new TransactionTemplate(txManager);
            this.jdbcClient = JdbcClient.create(dataSource);
        }

        public JdbcClient jdbcClient() {
            return jdbcClient;
        }

        public DataSource dataSource() {
            return dataSource;
        }

        /**
         * A transaction manager that stamps {@code app.tenant_id} from {@code tenantContext} at every transaction
         * start - the production shape of {@code TenantStampingTransactionManager}, mirrored in test scope because
         * that class is package-private in tenancy. With no tenant bound nothing is stamped (fails closed).
         */
        public org.springframework.transaction.PlatformTransactionManager stampingManager(TenantContext tenantContext) {
            return new org.springframework.jdbc.support.JdbcTransactionManager(dataSource) {
                @Override
                protected void prepareTransactionalConnection(Connection connection,
                        org.springframework.transaction.TransactionDefinition definition) throws SQLException {
                    super.prepareTransactionalConnection(connection, definition);
                    Optional<UUID> tenantId = tenantContext.currentTenantId();
                    if (tenantId.isPresent()) {
                        try (PreparedStatement set = connection.prepareStatement(
                                "select set_config('app.tenant_id', ?, true)")) {
                            set.setString(1, tenantId.get().toString());
                            set.execute();
                        }
                    }
                }
            };
        }

        /** Runs {@code work} in one transaction with {@code app.tenant_id} stamped (set_config local, R11). */
        public void inTenantTx(UUID tenantId, Runnable work) {
            transactionTemplate.executeWithoutResult(status -> {
                stamp(tenantId);
                work.run();
            });
        }

        /** Like {@link #inTenantTx} but returns a value. */
        public <T> T inTenantTx(UUID tenantId, java.util.function.Supplier<T> work) {
            return transactionTemplate.execute(status -> {
                stamp(tenantId);
                return work.get();
            });
        }

        /** Runs {@code work} in one transaction with no tenant stamped (the error-deny RLS proof). */
        public void inBareTx(Runnable work) {
            transactionTemplate.executeWithoutResult(status -> work.run());
        }

        private void stamp(UUID tenantId) {
            Connection connection = null;
            try {
                connection = org.springframework.jdbc.datasource.DataSourceUtils.doGetConnection(dataSource);
                try (PreparedStatement set = connection.prepareStatement(
                        "select set_config('app.tenant_id', ?, true)")) {
                    set.setString(1, tenantId.toString());
                    set.execute();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("cannot stamp the tenant", e);
            } finally {
                if (connection != null) {
                    org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection, dataSource);
                }
            }
        }
    }

    private static String randomPassword() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
