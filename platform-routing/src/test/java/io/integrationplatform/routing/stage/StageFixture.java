package io.integrationplatform.routing.stage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Lane A container fixture (T-111/T-113/T-181, E8 wave 1): one PostgreSQL per test JVM migrated with every
 * module's migrations (tenancy, outbox, lane A's V2_1_810-811 message/payload and the V2_3_811 work-tenant
 * function, delivery, configuration), set up the way a deployment is - an owner login that Flyway runs as
 * (and that would run the partition maintenance, T6), and the restricted {@code ipaas_app} login whose grants
 * are the RLS proofs. The Kafka broker comes from the wave-0 harness. Passwords are random per run, in memory
 * only (R07). Singleton style follows platform-delivery's DeliveryTestDatabase.
 */
final class StageFixture {

    static final String OWNER = "ipaas_migrator";
    static final String APP_LOGIN = "ipaas_app_login";
    static final String APP_ROLE = "ipaas_app";

    static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");
    static final UUID INTERFACE_ID = UUID.fromString("11111111-0000-4000-8000-000000000001");
    static final UUID INTERFACE_VERSION_ID = UUID.fromString("12121212-0000-4000-8000-000000000002");
    static final UUID SOURCE_CONTRACT_VERSION_ID = UUID.fromString("13131313-0000-4000-8000-000000000003");

    private static StageFixture instance;
    private static RuntimeException setupFailure;

    private final PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:17.11-alpine");
    private final String ownerPassword = randomPassword();
    private final String appPassword = randomPassword();
    private long messageSeq;

    private StageFixture() {
    }

    static synchronized StageFixture get() {
        if (setupFailure != null) {
            throw setupFailure;
        }
        if (instance == null) {
            StageFixture fixture = new StageFixture();
            try {
                fixture.container.start();
                fixture.setUp();
            } catch (Exception e) {
                setupFailure = new IllegalStateException("lane A stage fixture setup failed: " + e.getMessage(), e);
                throw setupFailure;
            }
            instance = fixture;
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

    /** Seeds one ACCEPTED message row for {@code tenantId}, accepted now (it lands in today's partition). */
    synchronized UUID seedMessage(UUID tenantId) throws SQLException {
        messageSeq++;
        UUID id = UUID.fromString("33333333-0000-4000-8000-" + String.format("%012d", messageSeq));
        try (Connection owner = owner();
                PreparedStatement insert = owner.prepareStatement(
                        "insert into public.message (id, tenant_id, interface_id, interface_version_id,"
                                + " source_contract_version_id, business_key, accepted_at)"
                                + " values (?, ?, ?, ?, ?, ?, now())")) {
            insert.setObject(1, id);
            insert.setObject(2, tenantId);
            insert.setObject(3, INTERFACE_ID);
            insert.setObject(4, INTERFACE_VERSION_ID);
            insert.setObject(5, SOURCE_CONTRACT_VERSION_ID);
            insert.setString(6, "business-key-" + messageSeq);
            insert.executeUpdate();
        }
        return id;
    }

    /** The connection pool login the consumers run as. */
    Connection app() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), APP_LOGIN, appPassword);
    }

    Connection owner() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), OWNER, ownerPassword);
    }

    Connection superuser() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
                container.getPassword());
    }

    /** The work-tenant loader of ADR-011 Decision 8, exercised exactly as a stage consumer does. */
    StageConsumer.ProtectedRows protectedRows() {
        return new StageConsumer.ProtectedRows() {
            @Override
            public UUID tenantFor(WorkKind kind, UUID id) {
                try (Connection app = app();
                        PreparedStatement lookup = app.prepareStatement(
                                "select public.ipaas_work_tenant(?, ?)")) {
                    lookup.setString(1, kind.name());
                    lookup.setObject(2, id);
                    try (ResultSet found = lookup.executeQuery()) {
                        found.next();
                        return found.getObject(1, UUID.class);
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("the work-tenant lookup failed: " + e.getMessage(), e);
                }
            }

            @Override
            public boolean visibleTo(UUID tenantId, WorkKind kind, UUID id) {
                String table = kind == WorkKind.MESSAGE ? "public.message" : "public.delivery";
                try (Connection app = app()) {
                    app.setAutoCommit(false);
                    try (PreparedStatement stamp = app.prepareStatement(
                            "select set_config('app.tenant_id', ?, true)");
                            PreparedStatement visible = app.prepareStatement(
                                    "select exists (select 1 from " + table + " where id = ?)")) {
                        stamp.setString(1, tenantId.toString());
                        stamp.execute();
                        visible.setObject(1, id);
                        try (ResultSet found = visible.executeQuery()) {
                            found.next();
                            return found.getBoolean(1);
                        }
                    } finally {
                        app.rollback();
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("the protected-row load failed: " + e.getMessage(), e);
                }
            }
        };
    }

    /** The broker host:port, from the wave-0 harness (one broker, three topics, auto-create off). */
    String kafkaBootstrapServers() {
        return io.integrationplatform.routing.kafka.KafkaHarness.get().bootstrapServers();
    }

    private static String randomPassword() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}