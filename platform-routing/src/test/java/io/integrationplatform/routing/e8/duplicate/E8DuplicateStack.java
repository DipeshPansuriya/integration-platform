package io.integrationplatform.routing.e8.duplicate;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * The two real containers the post-crash duplicate proof drives (T-114, E8-F1-S2 "Containers: PostgreSQL, Kafka"):
 * the real Kafka broker comes from the wave 0 {@code KafkaHarness} ({@code apache/kafka:4.3.1}, auto-creation off,
 * the three ADR-010 topics) and this fixture adds the real PostgreSQL side the relay and the duplicate guard run
 * on - one {@code postgres:17.11-alpine} per test JVM migrated with every module's migrations through
 * {@code classpath:db/migration}, the roles built the way a deployment is: {@code ipaas_e8d_migrator} runs Flyway
 * and owns the schema (never the application role, T6), and the restricted {@code ipaas_app} role carries the
 * login the SQL runs as under row-level security with {@code app.tenant_id} stamped per transaction (R07/R08/R11).
 * Passwords are random per run and live only in memory (R07).
 *
 * <p>Why a plain {@link GenericContainer} and not {@code PostgreSQLContainer}: the wave 0 harness commit added the
 * Kafka Testcontainers artifacts to platform-routing's pom (test scope) and no postgresql one, and the E8 lanes own
 * only their test trees - so lane C1 runs the same pinned image the platform's other lane fixtures pin, as a plain
 * container, with no stand-in database of any kind.
 */
public final class E8DuplicateStack {

    /** Tenant A of every lane fixture (DeliveryTestDatabase convention). */
    public static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");

    /** The bucket this evidence uses; the relay loops every bucket, so any one works. */
    public static final int KEY_BUCKET = 17;

    public static final String MIGRATOR = "ipaas_e8d_migrator";
    public static final String APP_LOGIN = "ipaas_e8d_login";
    public static final String APP_ROLE = "ipaas_app";

    private static final String DATABASE = "ipass_e8_duplicate";
    private static final String ADMIN_LOGIN = "e8d_admin";

    private static E8DuplicateStack instance;
    private static RuntimeException setupFailure;

    /** postgres:17.11-alpine - the exact image the delivery/config/ingest fixtures pin for PostgreSQL (R05). */
    private final GenericContainer<?> postgres = new GenericContainer<>("postgres:17.11-alpine")
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", ADMIN_LOGIN)
            .withEnv("POSTGRES_PASSWORD", "e8d-" + UUID.randomUUID().toString().replace("-", ""))
            .withExposedPorts(5432);

    private final String migratorPassword = randomPassword();
    private final String appPassword = randomPassword();
    private HikariDataSource pool;
    private JdbcClient jdbc;

    private E8DuplicateStack() {
    }

    /** The one stack per test JVM; starts the real PostgreSQL (Kafka comes with {@link #kafka()}). */
    public static synchronized E8DuplicateStack get() {
        if (setupFailure != null) {
            throw setupFailure;
        }
        if (instance == null) {
            E8DuplicateStack stack = new E8DuplicateStack();
            try {
                stack.setUp();
            } catch (Exception e) {
                setupFailure = new IllegalStateException("E8 lane C1 database setup failed: " + e.getMessage(), e);
                throw setupFailure;
            }
            instance = stack;
        }
        return instance;
    }

    private void setUp() throws Exception {
        postgres.start();
        String url = jdbcUrl();
        awaitConnectable(url);
        try (Connection admin = superuser(); Statement sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + MIGRATOR + " LOGIN CREATEROLE PASSWORD '" + migratorPassword + "'");
            sql.execute("ALTER DATABASE " + DATABASE + " OWNER TO " + MIGRATOR);
        }
        // Every module's migrations on the routing test classpath, exactly like DeliveryTestDatabase migrates.
        Flyway.configure()
                .dataSource(url, MIGRATOR, migratorPassword)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection admin = superuser(); Statement sql = admin.createStatement()) {
            // ipaas_app is the NOLOGIN role the migrations create; the login joins it (DeliveryTestDatabase pattern).
            sql.execute("CREATE ROLE " + APP_LOGIN + " LOGIN PASSWORD '" + appPassword + "' IN ROLE " + APP_ROLE);
            sql.execute("insert into public.tenant (id, code, status, time_zone) values"
                    + " ('" + TENANT_A + "', 'tenant-a', 'ACTIVE', 'Etc/UTC')");
        }
        pool = new HikariDataSource(config());
        jdbc = JdbcClient.create(pool);
    }

    /** The JDBC client the relay's access and the duplicate guard run SQL through (the {@code ipaas_app} pool). */
    public JdbcClient appJdbcClient() {
        return jdbc;
    }

    /**
     * The relay's transaction unit: one transaction with {@code app.tenant_id} stamped from the supplier (the
     * relay binds its tenant on the thread before calling {@code transactions.execute}, so the stamp reads the
     * freshly bound one - the way RelayTenantContext + the stamping transaction manager are wired on
     * platform-app).
     */
    public TransactionOperations stampedTransactions(Supplier<Optional<UUID>> tenants) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(pool);
        TransactionTemplate template = new TransactionTemplate(manager);
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return template.execute(status -> {
                    stamp(tenants);
                    return action.doInTransaction(status);
                });
            }
        };
    }

    /** Runs {@code work} as one stamped tenant transaction (this fixture's tenant) and returns its value. */
    public <T> T inTenantTx(UUID tenant, TransactionCallback<T> work) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(pool);
        TransactionTemplate template = new TransactionTemplate(manager);
        return template.execute(status -> {
            stamp(tenant);
            return work.doInTransaction(status);
        });
    }

    /** Inserts one accepted-message outbox row as the owner (fixture setup), returning its row id. */
    public long insertOutboxRow(UUID tenant, io.integrationplatform.ingest.port.OutboxTopic topic, UUID aggregateId,
            String recordKey, String traceparent) throws SQLException {
        try (Connection owner = owner(); PreparedStatement insert = owner.prepareStatement(
                "insert into public.outbox (tenant_id, topic, aggregate_id, record_key, key_bucket, traceparent,"
                        + " available_at) values (?, ?, ?, ?, ?, ?, now() - interval '1 second') returning id")) {
            insert.setObject(1, tenant);
            insert.setString(2, topic.topicName());
            insert.setObject(3, aggregateId);
            insert.setString(4, recordKey);
            insert.setInt(5, KEY_BUCKET);
            insert.setString(6, traceparent);
            ResultSet keys = insert.executeQuery();
            keys.next();
            return keys.getLong(1);
        }
    }

    /** How many claims the idempotency-key table holds for one key, seen across every tenant (owner view). */
    public int idempotencyClaimCount(String idempotencyKey) throws SQLException {
        try (Connection owner = owner(); PreparedStatement count = owner.prepareStatement(
                "select count(*) from public.idempotency_key where idempotency_key = ?")) {
            count.setString(1, idempotencyKey);
            ResultSet rows = count.executeQuery();
            rows.next();
            return rows.getInt(1);
        }
    }

    /** Whether the outbox row is still unpublished (the crash window's state: the mark never landed). */
    public boolean outboxRowUnpublished(long outboxId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement select = owner.prepareStatement(
                "select published_at from public.outbox where id = ?")) {
            select.setLong(1, outboxId);
            ResultSet rows = select.executeQuery();
            rows.next();
            return rows.getTimestamp(1) == null;
        }
    }

    private void stamp(Supplier<Optional<UUID>> tenants) {
        stamp(tenants.get().orElseThrow());
    }

    private void stamp(UUID tenant) {
        // the tenant is a UUID constant of this fixture, never free text; the set_config is per transaction (R11)
        new org.springframework.jdbc.core.JdbcTemplate(pool).execute((Connection con) -> {
            try (Statement set = con.createStatement()) {
                set.execute("select set_config('app.tenant_id', '" + tenant + "', true)");
            }
            return null;
        });
    }

    private HikariConfig config() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl());
        config.setUsername(APP_LOGIN);
        config.setPassword(appPassword);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(4);
        config.setPoolName("e8-lane-c1-pool");
        return config;
    }

    private String jdbcUrl() {
        return "jdbc:postgresql://localhost:" + postgres.getMappedPort(5432) + "/" + DATABASE;
    }

    private void awaitConnectable(String url) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        SQLException last = null;
        while (System.currentTimeMillis() < deadline) {
            try (Connection probe = superuser()) {
                probe.getClientInfo();
                return;
            } catch (SQLException attempt) {
                last = attempt;
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("postgres not connectable within 60s", last);
    }

    private Connection superuser() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), ADMIN_LOGIN,
                (String) postgres.getEnvMap().get("POSTGRES_PASSWORD"));
    }

    private Connection owner() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), MIGRATOR, migratorPassword);
    }

    private static String randomPassword() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}