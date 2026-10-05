package io.integrationplatform.ingest.chaos;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Lane D chaos fixture (T-009): one pinned PostgreSQL per test JVM, set up the way a deployment is - the same shape
 * as platform-tenancy's {@code RlsTestDatabase} (E1 precedent; reused as a pattern, not as a class, because
 * platform-tenancy exports no test-jar and poms are frozen). The Flyway owner login runs every
 * {@code classpath:db/migration} file (platform-tenancy's V1_3_* plus the frozen {@code V2_0_1__outbox.sql} of this
 * module), then the restricted application login is created outside Flyway (R07: passwords are random per run and
 * live only in memory) and the two ACTIVE tenants the chaos suite seeds outbox rows for are inserted as the owner.
 *
 * <p>Image pinned per R05: {@code postgres:17.11-alpine}, the same tag as
 * platform-tenancy's {@code TenancyTestImages.POSTGRES} and compose.
 */
final class OutboxChaosDatabase {

    /** R05: exact pinned tag - identical to TenancyTestImages.POSTGRES (kept here because no test-jar is exported). */
    static final String POSTGRES_IMAGE = "postgres:17.11-alpine";

    static final String OWNER = "ipaas_migrator";
    static final String APP_LOGIN = "ipaas_app_login";
    static final String APP_ROLE = "ipaas_app";

    static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");

    /** One outbox row as the database holds it - the tie-out target of the cross-tenant assertion. */
    record OutboxRow(UUID tenantId, String topic, UUID aggregateId, String recordKey, boolean published) {
    }

    private static OutboxChaosDatabase instance;
    private static RuntimeException setupFailure;

    private final PostgreSQLContainer container;
    private final String ownerPassword = randomPassword();
    private final String appPassword = randomPassword();

    private OutboxChaosDatabase(PostgreSQLContainer container) {
        this.container = container;
    }

    /** The started, migrated and seeded database; rethrows the first setup failure to every caller. */
    static synchronized OutboxChaosDatabase get() {
        if (setupFailure != null) {
            throw setupFailure;
        }
        if (instance == null) {
            PostgreSQLContainer container = new PostgreSQLContainer(POSTGRES_IMAGE);
            container.start();
            OutboxChaosDatabase database = new OutboxChaosDatabase(container);
            try {
                database.setUp();
            } catch (Exception e) {
                setupFailure = new IllegalStateException("lane D chaos database setup failed: " + e.getMessage(), e);
                throw setupFailure;
            }
            instance = database;
        }
        return instance;
    }

    private void setUp() throws SQLException {
        try (Connection admin = superuserConnection(); Statement sql = admin.createStatement()) {
            // DDL cannot take bind parameters; the passwords are random hex generated in this JVM, never from input.
            sql.execute("CREATE ROLE " + OWNER + " LOGIN CREATEROLE PASSWORD '" + ownerPassword + "'");
            sql.execute("ALTER DATABASE " + container.getDatabaseName() + " OWNER TO " + OWNER);
        }
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), OWNER, ownerPassword)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection admin = superuserConnection(); Statement sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + APP_LOGIN + " LOGIN PASSWORD '" + appPassword + "' IN ROLE " + APP_ROLE);
        }
        seedTenants();
    }

    private void seedTenants() throws SQLException {
        try (Connection owner = ownerConnection();
                PreparedStatement tenant = owner.prepareStatement(
                        "insert into public.tenant (id, code, status, time_zone) values (?::uuid, ?, ?, ?)")) {
            addTenant(tenant, TENANT_A, "tenant-a", "Asia/Kolkata");
            addTenant(tenant, TENANT_B, "tenant-b", "Europe/Berlin");
            tenant.executeBatch();
        }
    }

    private static void addTenant(PreparedStatement insert, UUID id, String code, String zone) throws SQLException {
        insert.setString(1, id.toString());
        insert.setString(2, code);
        insert.setString(3, "ACTIVE");
        insert.setString(4, zone);
        insert.addBatch();
    }

    /** A Hikari pool as the restricted application login - what the Spring context hands the lanes' beans. */
    HikariDataSource appPool(int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl());
        config.setUsername(APP_LOGIN);
        config.setPassword(appPassword);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);
        config.setPoolName("lane-d-chaos-pool");
        return new HikariDataSource(config);
    }

    /**
     * Truncates the outbox for the next test, restarting the identity (fixture owner login: the assertions and the
     * cycle boundary of the suite read across tenants, which the app login's row-level security never allows).
     */
    void truncateOutbox() throws SQLException {
        try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
            sql.execute("truncate table public.outbox restart identity");
        }
    }

    /** Rows still unmarked - the RPO count, read by the owner so it sees every tenant (fixture, not app path). */
    long countUnpublished() throws SQLException {
        try (Connection owner = ownerConnection();
                PreparedStatement count = owner.prepareStatement(
                        "select count(*) from public.outbox where published_at is null");
                ResultSet rs = count.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** The stored row behind one outbox id - the tie-out of the cross-tenant assertion. */
    OutboxRow readOutboxRow(long outboxId) throws SQLException {
        try (Connection owner = ownerConnection();
                PreparedStatement row = owner.prepareStatement(
                        "select tenant_id, topic, aggregate_id, record_key, published_at is not null from public.outbox"
                                + " where id = ?")) {
            row.setLong(1, outboxId);
            try (ResultSet rs = row.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new OutboxRow(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getBoolean(5));
            }
        }
    }

    Connection ownerConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(container.getJdbcUrl(), OWNER, ownerPassword);
    }

    private Connection superuserConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static String randomPassword() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
