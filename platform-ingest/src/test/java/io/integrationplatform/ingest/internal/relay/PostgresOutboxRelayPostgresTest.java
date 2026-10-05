package io.integrationplatform.ingest.internal.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.ingest.internal.publish.InMemoryPublishPort;
import io.integrationplatform.ingest.port.OutboxTopic;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T-008 against the REAL database (E2-F1-S1): the relay's claim is {@code FOR UPDATE SKIP LOCKED} under
 * row-level security on the table {@code V2_0_1__outbox.sql} creates, the app connects as the restricted login
 * (member of {@code ipaas_app}, never the Flyway owner - the PlatformTestWiring shape of platform-app, which
 * this module cannot import), every transaction stamps {@code app.tenant_id} as a local setting (R11), and an
 * unbound read of the outbox fails closed with the error-deny policy. Two relays racing one due set claim
 * disjoint rows and publish every row exactly once; the mark commits with the claim, so what was published but
 * not marked re-appears for the next cycle (at-least-once, RPO zero - proven by the fake sibling).
 *
 * <p>Runs only with {@code -Pcontainer} under the one-suite-at-a-time Docker lock (R03, R06); pinned image (R05).
 */
@Tag("container")
@Testcontainers
class PostgresOutboxRelayPostgresTest {

    /** Pinned image (R05) - the same tag {@code TestImages.POSTGRES} names in platform-app's suite. */
    private static final String POSTGRES_IMAGE = "postgres:17.11-alpine";

    private static final UUID TENANT_A = id("tenant-a");
    private static final UUID TENANT_B = id("tenant-b");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The restricted login: created once after Flyway, random password in memory only (R07). */
    private static String loginUser;
    private static String loginPassword;

    private DataSource appPool;
    private RelayTenantContext context;
    private JdbcOutboxAccess access;
    private TransactionTemplate transactions;

    @BeforeAll
    static void migrateAsOwnerAndCreateTheRestrictedLogin() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        loginUser = "t31_relay_login";
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        loginPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(secret); // url-safe charset: no quotes
        try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
            // fresh container (R05): the role cannot exist yet; the schema privileges come from ipaas_app membership
            statement.execute("CREATE ROLE " + loginUser + " LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB "
                    + "NOCREATEROLE NOREPLICATION PASSWORD '" + loginPassword + "'");
            statement.execute("GRANT ipaas_app TO " + loginUser);
            statement.execute("INSERT INTO tenant (id, code, status, time_zone) VALUES ('" + TENANT_A
                    + "', 't31-a', 'ACTIVE', 'Etc/UTC'), ('" + TENANT_B + "', 't31-b', 'ACTIVE', 'Etc/UTC')");
        }
    }

    @BeforeEach
    void wireTheRealRelayStack() throws Exception {
        try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("truncate table outbox"); // each test seeds its own due set
        }
        DriverManagerDataSource pool = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), loginUser, loginPassword);
        appPool = pool;
        context = new RelayTenantContext();
        access = new JdbcOutboxAccess(JdbcClient.create(pool), context);
        transactions = new TransactionTemplate(new StampingManager(pool));
    }

    @Test
    void aFullCyclePublishesAndMarksEveryDueRowOfEveryActiveTenantUnderRls() {
        seed(TENANT_A, 0, "key-a1");
        seed(TENANT_A, 3, "key-a2");
        seed(TENANT_B, 3, "key-b1");
        InMemoryPublishPort port = new InMemoryPublishPort();

        int marked = new PostgresOutboxRelay(() -> List.of(TENANT_A, TENANT_B), access, context, transactions,
                port, Duration.ofSeconds(5), 10).runOnce();

        assertThat(marked).as("every unpublished due row of both tenants").isEqualTo(3);
        assertThat(port.published()).hasSize(3);
        assertThat(countWhere("published_at is null")).as("nothing left unmarked").isZero();
        assertThat(rowsMarkedOf(TENANT_A)).isEqualTo(2);
        assertThat(rowsMarkedOf(TENANT_B)).as("tenant B's row was claimed under its own binding").isEqualTo(1);
    }

    @Test
    void aRelayBoundToTheWrongTenantsSeesNothingAndTenantBsRowSurvives() {
        long rowA1 = seed(TENANT_A, 5, "key-a1");
        long rowB1 = seed(TENANT_B, 5, "key-b1");

        int marked = new PostgresOutboxRelay(() -> List.of(TENANT_A), access, context, transactions,
                new InMemoryPublishPort(), Duration.ofSeconds(5), 10).runOnce();

        assertThat(marked).as("RLS + tenant predicate: only tenant A's row is claimable").isEqualTo(1);
        assertThat(publishedOf(rowA1)).isTrue();
        assertThat(publishedOf(rowB1)).as("tenant B's row is untouched - the next tenant loop marks it").isFalse();
    }

    @Test
    void anUnboundReadOfTheOutboxFailsClosedAtTheDatabase() {
        seed(TENANT_A, 7, "key-a1");

        assertThatThrownBy(() -> new JdbcTemplate(appPool)
                .queryForList("select id from (select id from outbox where published_at is null order by id"
                        + " limit 1 for update skip locked) c"))
                .as("error-deny RLS (ADR-009 Decision 5): no app.tenant_id, no statement")
                .getRootCause()
                .hasMessageContaining("app.tenant_id");
    }

    @Test
    void twoRacingRelaysClaimDisjointRowsAndPublishEveryRowExactlyOnce() throws Exception {
        for (int row = 0; row < 8; row++) {
            seed(TENANT_A, 0, "key-" + row);
        }
        InMemoryPublishPort portOne = new InMemoryPublishPort();
        InMemoryPublishPort portTwo = new InMemoryPublishPort();
        PostgresOutboxRelay relayOne = relay(portOne, 2);
        PostgresOutboxRelay relayTwo = relay(portTwo, 2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread one = new Thread(() -> {
            try {
                go.await();
                relayOne.runOnce();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        Thread two = new Thread(() -> {
            try {
                go.await();
                relayTwo.runOnce();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        one.start();
        two.start();
        go.countDown();
        one.join();
        two.join();

        assertThat(failure.get()).isNull();
        List<Long> published = java.util.stream.Stream
                .concat(portOne.published().stream(), portTwo.published().stream())
                .map(io.integrationplatform.ingest.port.OutboundRecord::outboxId)
                .sorted().toList();
        assertThat(published).as("SKIP LOCKED: no row claimed twice, none lost").hasSize(8).doesNotHaveDuplicates();
        assertThat(countWhere("published_at is null")).as("everything marked").isZero();
    }

    // ---------- wiring and helpers ----------

    private PostgresOutboxRelay relay(InMemoryPublishPort port, int batchSize) {
        return new PostgresOutboxRelay(() -> List.of(TENANT_A), access, context, transactions, port,
                Duration.ofSeconds(5), batchSize);
    }

    private final class StampingManager extends JdbcTransactionManager {

        private StampingManager(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                throws SQLException {
            super.prepareTransactionalConnection(connection, definition);
            // the production manager stamps exactly this way on the same SQL (TenantStampingTransactionManager)
            try (PreparedStatement stamp = connection.prepareStatement(
                    "select set_config('app.tenant_id', ?, true)")) {
                stamp.setString(1, context.requireTenantId().toString());
                stamp.execute();
            }
        }
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long seed(UUID tenant, int keyBucket, String recordKey) {
        try (Connection owner = ownerConnection();
                PreparedStatement insert = owner.prepareStatement(
                        "insert into outbox (tenant_id, topic, aggregate_id, record_key, key_bucket, available_at)"
                                + " values (?::uuid, ?, ?::uuid, ?, ?, now() - interval '5 seconds') returning id")) {
            insert.setString(1, tenant.toString());
            insert.setString(2, OutboxTopic.MESSAGE_ACCEPTED.topicName());
            insert.setString(3, UUID.nameUUIDFromBytes(("agg-" + recordKey).getBytes(StandardCharsets.UTF_8)).toString());
            insert.setString(4, recordKey);
            insert.setInt(5, keyBucket);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed the outbox", e);
        }
    }

    private long countWhere(String condition) {
        try (Connection owner = ownerConnection();
                Statement statement = owner.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from outbox where " + condition)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int rowsMarkedOf(UUID tenant) {
        try (Connection owner = ownerConnection();
                PreparedStatement count = owner.prepareStatement(
                        "select count(*) from outbox where tenant_id = ?::uuid and published_at is not null")) {
            count.setString(1, tenant.toString());
            try (ResultSet rs = count.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean publishedOf(long outboxId) {
        try (Connection owner = ownerConnection();
                PreparedStatement check = owner.prepareStatement(
                        "select published_at is not null from outbox where id = ?")) {
            check.setLong(1, outboxId);
            try (ResultSet rs = check.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
