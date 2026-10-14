package io.integrationplatform.routing.publish;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.ingest.internal.outbox.JdbcOutboxWriter;
import io.integrationplatform.ingest.internal.relay.ClaimedOutboxRow;
import io.integrationplatform.ingest.internal.relay.PostgresOutboxRelay;
import io.integrationplatform.ingest.internal.relay.RelayTenantContext;
import io.integrationplatform.ingest.internal.relay.OutboxRowAccess;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.routing.kafka.KafkaHarness;
import io.integrationplatform.routing.topics.Topics;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T-110 (E8-F1-S1 gate) and T-112 (E8-F1-S2) against a REAL PostgreSQL outbox (row-level security, the
 * restricted app login) and the REAL broker of {@link KafkaHarness} (T-044): 100 updates for one (tenant,
 * interface, business key) pass outbox -&gt; relay -&gt; the Kafka publish port -&gt; consumer in acceptance order,
 * every record key is SHA-256 of (tenant, interface, business key) (E8-C2), and a stored traceparent T reaches
 * the record as its only header - the publish half of "the consumer's span is a child of T" (the consumer-side
 * span is lane A's, T-111). Runs under the {@code container} tag only (R03/R06), one Docker suite at a time.
 */
@Tag("container")
@Testcontainers
class KafkaPublishPortContainerTest {

    /** Pinned image (R05) - the pin the ingest relay's container suite and platform-app's pin checker accept. */
    private static final String POSTGRES_IMAGE = "postgres:17.11-alpine";

    private static final UUID TENANT = UUID.nameUUIDFromBytes("t61-lane-b".getBytes(StandardCharsets.UTF_8));
    private static final String INTERFACE_CODE = "interface-1";
    private static final String BUSINESS_KEY = "business-42";
    /** The record key of the ordering scenario: SHA-256 of (tenant, interface, business key) (E8-C2). */
    private static final String ORDERING_KEY = sha256Hex(TENANT + "\u0000" + INTERFACE_CODE + "\u0000" + BUSINESS_KEY);
    private static final String VALUE_PREFIX = "{\"v\":1,\"type\":\"message.accepted\",\"id\":\"";
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);

    /** E8 tail: the relay's publish metrics land here - one registry per test instance. */
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    private static String loginUser;
    private static String loginPassword;

    private DataSource appPool;
    private TransactionTemplate transactions;

    @BeforeAll
    static void migrateAsOwnerAndCreateTheRestrictedLogin() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        loginUser = "t61_publish_login";
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        loginPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(secret); // url-safe: no quotes
        try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("CREATE ROLE " + loginUser + " LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB "
                    + "NOCREATEROLE NOREPLICATION PASSWORD '" + loginPassword + "'");
            statement.execute("GRANT ipaas_app TO " + loginUser);
            statement.execute("INSERT INTO tenant (id, code, status, time_zone) VALUES ('" + TENANT
                    + "', 't61-b', 'ACTIVE', 'Etc/UTC')");
        }
    }

    @BeforeEach
    void wireTheOutboxToKafkaStack() {
        try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("truncate table outbox"); // each test seeds its own updates (the broker is shared)
        } catch (SQLException e) {
            throw new IllegalStateException("could not clear the outbox", e);
        }
        appPool = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), loginUser, loginPassword);
        transactions = new TransactionTemplate(new StampingManager(appPool, TenantContextFixed.TENANT_SUPPLIER));
    }

    @AfterEach
    void clearTheOutboxAgain() {
        try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("truncate table outbox");
        } catch (SQLException e) {
            throw new IllegalStateException("could not clear the outbox", e);
        }
    }

    /**
     * Gherkin '100 same-key updates in sequence' (E8-C2): 100 updates for one (tenant, interface, business
     * key) enter the outbox in acceptance order; the relay publishes them through the Kafka port; a consumer
     * reads them back in sequence 1 to 100, every record key the SHA-256 of the triple, the value ids-only.
     */
    @Test
    void oneHundredSameKeyUpdatesAreConsumedInTheOrderTheyWereAccepted() {
        List<UUID> accepted = new ArrayList<>();
        JdbcOutboxWriter writer = new JdbcOutboxWriter(JdbcClient.create(appPool), TenantContextFixed.INSTANCE);
        transactions.execute(status -> {
            for (int step = 1; step <= 100; step++) {
                UUID messageId = UUID.nameUUIDFromBytes(("update-" + step).getBytes(StandardCharsets.UTF_8));
                writer.append(new OutboxEntry(OutboxTopic.MESSAGE_ACCEPTED, messageId, ORDERING_KEY, null, null));
                accepted.add(messageId);
            }
            return null;
        });

        runRelay();
        runRelay();
        runRelay();
        runRelay();

        List<ConsumerRecord<String, String>> records = consume(Topics.MESSAGE_ACCEPTED,
                record -> record.key().equals(ORDERING_KEY), 100);
        assertThat(records).as("every one of the 100 updates reaches the consumer, none twice").hasSize(100);
        assertThat(records)
                .as("none out of place - sequence 1 to 100, no later update overtaking an earlier one")
                .extracting(record -> accepted.indexOf(idOf(record.value())))
                .isSorted();
        for (ConsumerRecord<String, String> record : records) {
            assertThat(record.value())
                    .as("ids only - no payload, no tenant id on the wire (ADR-011 Decision 7)")
                    .startsWith(VALUE_PREFIX).containsOnlyOnce("\"id\":\"").endsWith("\"}")
                    .doesNotContain("payload").doesNotContain("tenant");
        }
        // E8 tail: the real-broker relay run is counted and timed - 100 acknowledged successes, no failure.
        assertThat(meters.find(KafkaPublishPort.RECORDS_COUNTER)
                .tags("topic", Topics.MESSAGE_ACCEPTED, "outcome", "success", "failure", "none").counter())
                .as("the publish success counter is registered").isNotNull()
                .extracting(io.micrometer.core.instrument.Counter::count).isEqualTo(100.0);
        assertThat(meters.find(KafkaPublishPort.LATENCY_TIMER)
                .tags("topic", Topics.MESSAGE_ACCEPTED, "outcome", "success").timer())
                .as("the publish latency timer is registered").isNotNull()
                .extracting(io.micrometer.core.instrument.Timer::count).isEqualTo(100L);
        assertThat(meters.find(KafkaPublishPort.RECORDS_COUNTER).tag("outcome", "failure").counters())
                .as("no publish failed").isEmpty();
    }

    /**
     * Gherkin 'The trace survives the outbox' (T-112, E8-C5): a row that stored traceparent T is published by
     * the relay exactly carrying traceparent T as its one header. The consumer SPAN being a child of T is the
     * consumer side (lane A, T-111); this scenario pins the publish half the consumer builds on.
     */
    @Test
    void theTraceSurvivesTheOutboxAsTheRecordsOnlyHeader() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        UUID messageId = UUID.nameUUIDFromBytes("traced-update".getBytes(StandardCharsets.UTF_8));
        String traceKey = sha256Hex(TENANT + "\u0000" + INTERFACE_CODE + "\u0000" + "business-traced");

        JdbcOutboxWriter writer = new JdbcOutboxWriter(JdbcClient.create(appPool), TenantContextFixed.INSTANCE);
        transactions.execute(status -> {
            writer.append(new OutboxEntry(OutboxTopic.MESSAGE_ACCEPTED, messageId, traceKey, traceparent, null));
            return null;
        });

        runRelay();

        List<ConsumerRecord<String, String>> records = consume(Topics.MESSAGE_ACCEPTED,
                record -> record.key().equals(traceKey), 1);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).headers()).as("the only header the record carries").hasSize(1);
        assertThat(new String(records.get(0).headers().lastHeader("traceparent").value()))
                .as("the row's stored traceparent T restored on the wire").isEqualTo(traceparent);
    }

    // ---------- wiring and helpers ----------

    /** The id the record value carries out. */
    private static UUID idOf(String value) {
        return UUID.fromString(value.substring(VALUE_PREFIX.length(), value.length() - 2));
    }

    private void runRelay() {
        RelayTenantContext relayContext = new RelayTenantContext();
        new PostgresOutboxRelay(() -> List.of(TENANT), new TestRelayRowAccess(appPool, relayContext),
                relayContext, transactions, new KafkaPublishPort(kafkaProducer(), meters), ACK_TIMEOUT, 25).runOnce();
    }

    private static final class TenantContextFixed implements TenantContext {
        private static final TenantContextFixed INSTANCE = new TenantContextFixed();
        private static final java.util.function.Supplier<UUID> TENANT_SUPPLIER = () -> TENANT;

        @Override
        public Optional<UUID> currentTenantId() {
            return Optional.of(TENANT);
        }
    }

    /** Stamps {@code app.tenant_id} per transaction, the way the production manager does (R11). */
    private static final class StampingManager extends JdbcTransactionManager {

        private StampingManager(DataSource dataSource, java.util.function.Supplier<UUID> tenant) {
            super(dataSource);
            this.tenant = tenant;
        }

        private final java.util.function.Supplier<UUID> tenant;

        @Override
        protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                throws SQLException {
            super.prepareTransactionalConnection(connection, definition);
            try (PreparedStatement stamp = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                stamp.setString(1, tenant.get().toString());
                stamp.execute();
            }
        }
    }

    /**
     * The claim the relay runs in this test, exactly the statement of {@code JdbcOutboxAccess} (that class is
     * package-private inside ingest's relay package; its own container sibling proves the real claim and the
     * real key-bucket advisory lock against two relay instances). This test's access mirrors that statement
     * and the lock so the relay cycle runs against the real, row-level-security table.
     */
    private static final class TestRelayRowAccess extends TenantScopedRepository implements OutboxRowAccess {

        private static final String LOCK_KEY_BUCKET =
                "select pg_advisory_xact_lock(hashtextextended(:scope, 0))"
                        + " from (values (:own_tenant::uuid)) as scoped (tenant_id)";

        private static final String CLAIM_SELECT_FROM = """
                select id, topic, aggregate_id, record_key, traceparent, tenant_id \
                from (select id, topic, aggregate_id, record_key, traceparent, tenant_id \
                      from outbox \
                      where published_at is null and key_bucket = :key_bucket and available_at <= :available_at_or_before \
                      order by id \
                      limit :limit \
                      for update skip locked) claimed""";

        private static final String MARK_PUBLISH = "update outbox set published_at = now()";

        private final TenantContext relayTenantContext;

        TestRelayRowAccess(DataSource pool, RelayTenantContext relayTenantContext) {
            super(JdbcClient.create(pool), relayTenantContext);
            this.relayTenantContext = relayTenantContext;
        }

        @Override
        public void lockKeyBucket(int keyBucket) {
            UUID tenant = relayTenantContext.requireTenantId();
            query(LOCK_KEY_BUCKET, null,
                    Map.of("scope", tenant + ":" + keyBucket, "own_tenant", tenant),
                    (rs, rowNum) -> Boolean.TRUE).forEach(unused -> { });
        }

        @Override
        public List<ClaimedOutboxRow> claimUnpublished(int keyBucket, Instant availableAtOrBefore, int limit) {
            Timestamp availableAtOrBeforeTimestamp = Timestamp.from(availableAtOrBefore);
            return query(CLAIM_SELECT_FROM, null,
                    Map.of("key_bucket", keyBucket, "available_at_or_before", availableAtOrBeforeTimestamp,
                            "limit", limit),
                    (rs, rowNum) -> new ClaimedOutboxRow(rs.getLong("id"),
                            topicOf(rs.getString("topic")), rs.getObject("aggregate_id", UUID.class),
                            rs.getString("record_key"), rs.getString("traceparent")));
        }

        @Override
        public boolean markPublished(long outboxId) {
            return update(MARK_PUBLISH, "id = :outbox_id and published_at is null",
                    Map.of("outbox_id", outboxId)) == 1;
        }

        private static OutboxTopic topicOf(String stored) {
            for (OutboxTopic topic : OutboxTopic.values()) {
                if (topic.topicName().equals(stored)) {
                    return topic;
                }
            }
            throw new IllegalStateException("outbox.topic value not in the register: " + stored);
        }
    }

    private static Producer<String, String> kafkaProducer() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaHarness.get().bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(config);
    }

    /**
     * Polls the topic until {@code expected} records of the caller's own test data arrived (the harness
     * broker is a JVM singleton that earlier tests may have written to: the filter isolates this test's
     * records; a fresh consumer group plus earliest reset reads the whole topic).
     */
    private List<ConsumerRecord<String, String>> consume(String topic,
            Predicate<ConsumerRecord<String, String>> mine, int expected) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaHarness.get().bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "t61-lane-b-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(90).toMillis();
            while (found.size() < expected && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(2));
                for (ConsumerRecord<String, String> record : polled) {
                    if (mine.test(record)) {
                        found.add(record);
                    }
                }
            }
            return found;
        }
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return IntStream.range(0, digest.length)
                    .mapToObj(i -> String.format("%02x", digest[i]))
                    .reduce("", String::concat);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by ADR-011 Decision 7", impossible);
        }
    }
}