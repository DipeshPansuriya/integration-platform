package io.integrationplatform.tenancy.internal.rls;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.tenancy.TenancyTestImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Lane C container fixture (T-005, T-006, T-157): one PostgreSQL per test JVM, set up the way a deployment is.
 *
 * <ul>
 *   <li>{@value #OWNER} - the Flyway owner login: owns the database (so the {@code public} schema), may create roles,
 *       is NOT a superuser. Flyway runs every {@code classpath:db/migration} file as this login, so it owns the tables
 *       and the definer function (ADR-009 Decision 3).</li>
 *   <li>{@value #APP_LOGIN} - the application's restricted login, created outside Flyway and made a member of the
 *       NOLOGIN role {@code ipaas_app} (the migration creates that role; R07 keeps every password out of files).</li>
 *   <li>{@value #OUTSIDER} - a login that is NOT a member of {@code ipaas_app} (T5 negative: no EXECUTE).</li>
 * </ul>
 *
 * Passwords are random per run and live only in memory (R07). The directory is seeded by the owner, which bypasses
 * row-level security because no table is FORCE'd (the deliberate T-005 decision). Setup runs once; a failed setup (for
 * example the migrations are absent) is remembered and rethrown to every test, so the red run counts per test.
 */
public final class RlsTestDatabase {

    public static final String OWNER = "ipaas_migrator";
    public static final String APP_LOGIN = "ipaas_app_login";
    public static final String OUTSIDER = "ipaas_outsider";
    public static final String APP_ROLE = "ipaas_app";

    public static final String ISSUER = "https://idp.test/realms/platform";
    public static final String OTHER_ISSUER = "https://idp.test/realms/other";

    public static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    public static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");
    /** A SUSPENDED tenant. */
    public static final UUID TENANT_C = UUID.fromString("0000000c-0000-4000-8000-00000000000c");

    /** ACTIVE user of ACTIVE tenant A. */
    public static final UUID USER_ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000001");
    /** DISABLED user of tenant A. */
    public static final UUID USER_CAROL = UUID.fromString("ca401000-0000-4000-8000-000000000003");
    /** ACTIVE user of ACTIVE tenant B. */
    public static final UUID USER_BOB = UUID.fromString("b0b00000-0000-4000-8000-000000000002");
    /** ACTIVE user of SUSPENDED tenant C. */
    public static final UUID USER_DAVE = UUID.fromString("da5e0000-0000-4000-8000-000000000004");

    /** app_user rows per tenant in the seed. */
    public static final int USERS_OF_A = 2;
    public static final int USERS_OF_B = 1;

    private static RlsTestDatabase instance;
    private static RuntimeException setupFailure;

    private final PostgreSQLContainer container;
    private final String ownerPassword = randomPassword();
    private final String appPassword = randomPassword();
    private final String outsiderPassword = randomPassword();

    private RlsTestDatabase(PostgreSQLContainer container) {
        this.container = container;
    }

    /** The started, migrated and seeded database; rethrows the first setup failure to every caller. */
    public static synchronized RlsTestDatabase get() {
        if (setupFailure != null) {
            throw setupFailure;
        }
        if (instance == null) {
            PostgreSQLContainer container = new PostgreSQLContainer(TenancyTestImages.POSTGRES);
            container.start();
            RlsTestDatabase database = new RlsTestDatabase(container);
            try {
                database.setUp();
            } catch (Exception e) {
                setupFailure = new IllegalStateException("lane C database setup failed: " + e.getMessage(), e);
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
            sql.execute("CREATE ROLE " + OUTSIDER + " LOGIN PASSWORD '" + outsiderPassword + "'");
        }
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), OWNER, ownerPassword)
                // E4 T-098: the stub of platform-config's public.system, so source_credential gets its composite key.
                // T147: the stub of platform-config's scheduler.operator_credential, read by PrincipalTenantResolver.
                .locations("classpath:db/migration", "classpath:db/fixture/lane-c-system",
                        "classpath:db/fixture/scheduler")
                .load()
                .migrate();
        try (Connection admin = superuserConnection(); Statement sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + APP_LOGIN + " LOGIN PASSWORD '" + appPassword + "' IN ROLE " + APP_ROLE);
        }
        seed();
    }

    private void seed() throws SQLException {
        try (Connection owner = ownerConnection()) {
            try (PreparedStatement tenant = owner.prepareStatement(
                    "insert into public.tenant (id, code, status, time_zone) values (?::uuid, ?, ?, ?)")) {
                addTenant(tenant, TENANT_A, "tenant-a", "ACTIVE", "Asia/Kolkata");
                addTenant(tenant, TENANT_B, "tenant-b", "ACTIVE", "Europe/Berlin");
                addTenant(tenant, TENANT_C, "tenant-c", "SUSPENDED", "Etc/UTC");
                tenant.executeBatch();
            }
            try (PreparedStatement user = owner.prepareStatement(
                    "insert into public.app_user (id, tenant_id, issuer, subject, status)"
                            + " values (?::uuid, ?::uuid, ?, ?, ?)")) {
                addUser(user, USER_ALICE, TENANT_A, "alice", "ACTIVE");
                addUser(user, USER_CAROL, TENANT_A, "carol", "DISABLED");
                addUser(user, USER_BOB, TENANT_B, "bob", "ACTIVE");
                addUser(user, USER_DAVE, TENANT_C, "dave", "ACTIVE");
                user.executeBatch();
            }
        }
    }

    private static void addTenant(PreparedStatement insert, UUID id, String code, String status, String zone)
            throws SQLException {
        insert.setString(1, id.toString());
        insert.setString(2, code);
        insert.setString(3, status);
        insert.setString(4, zone);
        insert.addBatch();
    }

    private static void addUser(PreparedStatement insert, UUID id, UUID tenant, String subject, String status)
            throws SQLException {
        insert.setString(1, id.toString());
        insert.setString(2, tenant.toString());
        insert.setString(3, ISSUER);
        insert.setString(4, subject);
        insert.setString(5, status);
        insert.addBatch();
    }

    public String jdbcUrl() {
        return container.getJdbcUrl();
    }

    public Connection superuserConnection() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    public Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), OWNER, ownerPassword);
    }

    /** A fresh connection as the restricted application login (autocommit on, as the driver opens it). */
    public Connection appConnection() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), APP_LOGIN, appPassword);
    }

    public Connection outsiderConnection() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), OUTSIDER, outsiderPassword);
    }

    /** A Hikari pool as the restricted application login; size 1 proves connection reuse (ADR-007 D6 rule 4). */
    public HikariDataSource appPool(int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl());
        config.setUsername(APP_LOGIN);
        config.setPassword(appPassword);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);
        config.setPoolName("lane-c-app-pool");
        return new HikariDataSource(config);
    }

    private static String randomPassword() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
