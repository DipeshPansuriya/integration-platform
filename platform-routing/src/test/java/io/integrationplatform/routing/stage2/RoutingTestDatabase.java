package io.integrationplatform.routing.stage2;

import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Lane A1 container fixture (T-116/T-119): one PostgreSQL per test JVM migrated with every module's migrations
 * the routing test classpath carries (tenancy V1_3_*, ingest outbox V2_0_*, config V2_2_*, delivery V2_3_*),
 * set up the way a deployment is - the restricted {@code ipaas_app} login is what the application code runs as,
 * so the RLS and grant realities the code meets in production are met here. Pattern after lane C's delivery
 * fixture; passwords are random per run and live only in memory (R07).
 */
public final class RoutingTestDatabase {

    public static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");

    public static final UUID INTERFACE_ID = UUID.fromString("11111111-0000-4000-8000-000000000001");

    private static RoutingTestDatabase instance;
    private static RuntimeException setupFailure;

    private final org.testcontainers.postgresql.PostgreSQLContainer container =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.11-alpine");
    private final String ownerPassword = randomPassword();
    private final String appPassword = randomPassword();

    private RoutingTestDatabase() {
    }

    public static synchronized RoutingTestDatabase get() {
        if (setupFailure != null) {
            throw setupFailure;
        }
        if (instance == null) {
            RoutingTestDatabase database = new RoutingTestDatabase();
            try {
                database.container.start();
                database.setUp();
            } catch (Exception e) {
                setupFailure = new IllegalStateException("lane A1 routing database setup failed: " + e.getMessage(), e);
                throw setupFailure;
            }
            instance = database;
        }
        return instance;
    }

    private void setUp() throws Exception {
        String owner = "ipaas_migrator";
        try (Connection admin = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
                container.getPassword()); Statement sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + owner + " LOGIN CREATEROLE PASSWORD '" + ownerPassword + "'");
            sql.execute("ALTER DATABASE " + container.getDatabaseName() + " OWNER TO " + owner);
        }
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), owner, ownerPassword)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection ownerConnection = owner(owner); Statement sql = ownerConnection.createStatement()) {
            // ipaas_app already exists (V1_3_1): the login role joins it for the restricted application grants
            sql.execute("CREATE ROLE ipaas_app_login LOGIN PASSWORD '" + appPassword + "' IN ROLE ipaas_app");
            sql.execute("GRANT ipaas_app TO " + owner);
            sql.execute("insert into public.tenant (id, code, status, time_zone) values"
                    + " ('" + TENANT_A + "', 'tenant-a', 'ACTIVE', 'Etc/UTC')");
        }
    }

    private Connection owner(String owner) throws Exception {
        return DriverManager.getConnection(container.getJdbcUrl(), owner, ownerPassword);
    }

    /** An application pool as the restricted login; {@code size} must cover the test's concurrency. */
    public com.zaxxer.hikari.HikariDataSource appPool(int size) {
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl());
        config.setUsername("ipaas_app_login");
        config.setPassword(appPassword);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);
        config.setPoolName("lane-a1-routing-pool");
        return new com.zaxxer.hikari.HikariDataSource(config);
    }

    /** A {@link TenantContext} fixed to one tenant (tests supply their own implementation, seam javadoc). */
    public static TenantContext tenant(UUID tenantId) {
        return () -> Optional.of(tenantId);
    }

    /** The tenant-stamped transaction helper the delivery ports' callers run inside (R11). */
    public static final class TenantTx {

        private final DataSource dataSource;
        private final org.springframework.transaction.support.TransactionTemplate transactions;
        private final org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

        public TenantTx(DataSource dataSource) {
            this.dataSource = dataSource;
            this.transactions = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
            this.jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
        }

        public org.springframework.jdbc.core.simple.JdbcClient jdbcClient() {
            return jdbcClient;
        }

        /**
         * A {@link org.springframework.transaction.support.TransactionTemplate} that stamps
         * {@code app.tenant_id} from the context at every transaction start - the production shape of
         * {@code TenantStampingTransactionManager}, built here in test scope because that manager sits in a
         * package-private tenancy internal (test-only mirror, noted in the hand-off).
         */
        public org.springframework.transaction.support.TransactionTemplate stamping(TenantContext tenantContext) {
            var manager = new org.springframework.jdbc.support.JdbcTransactionManager(dataSource) {
                @Override
                protected void prepareTransactionalConnection(Connection connection,
                        org.springframework.transaction.TransactionDefinition definition)
                        throws java.sql.SQLException {
                    super.prepareTransactionalConnection(connection, definition);
                    tenantContext.currentTenantId().ifPresent(tenantId -> {
                        try (var stamp = connection.prepareStatement(
                                "select set_config('app.tenant_id', ?, true)")) {
                            stamp.setString(1, tenantId.toString());
                            stamp.execute();
                        } catch (java.sql.SQLException failure) {
                            throw new IllegalStateException("cannot stamp the tenant", failure);
                        }
                    });
                }
            };
            return new org.springframework.transaction.support.TransactionTemplate(manager);
        }

        /** Runs {@code work} in one transaction with {@code app.tenant_id} stamped (set_config local, R11). */
        public void inTenantTx(UUID tenantId, Runnable work) {
            transactions.executeWithoutResult(status -> {
                stamp(tenantId);
                work.run();
            });
        }

        private void stamp(UUID tenantId) {
            Connection connection = null;
            try {
                connection = org.springframework.jdbc.datasource.DataSourceUtils.doGetConnection(dataSource);
                try (var set = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                    set.setString(1, tenantId.toString());
                    set.execute();
                }
            } catch (Exception e) {
                throw new IllegalStateException("cannot stamp the tenant", e);
            } finally {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }

    private static String randomPassword() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}