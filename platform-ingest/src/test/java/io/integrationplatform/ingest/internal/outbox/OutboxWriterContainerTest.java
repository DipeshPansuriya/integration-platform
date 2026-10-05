package io.integrationplatform.ingest.internal.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T-007 against a real PostgreSQL (E2-F1-S1 tasks, ADR-005 D11): the writer appends inside the CALLER's open
 * transaction and never its own, returns the new id, and stores every {@link OutboxEntry} field the frozen
 * {@code V2_0_1__outbox.sql} names. Container group (R03): runs only with ./mvnw -Pcontainer, one Docker suite at a
 * time (R06). The connection here is the Flyway owner (Testcontainers default), which bypasses RLS - the app-role
 * proofs are in {@link OutboxRlsContainerTest}. The owner connection lets each test commit or roll back on purpose
 * (a test-managed @Transactional rollback could not show the committed side).
 *
 * <pre>
 * Scenario: Append commits and rolls back with the caller's transaction
 *   Given tenant A is bound and a transaction is open
 *   When the writer appends an entry and the transaction is killed
 *   Then no outbox row exists; committed with the transaction it does
 *
 * Scenario: The written row carries exactly what the entry and the tenant say
 *   When an entry is appended and committed
 *   Then tenant_id is the bound tenant, topic the enum's name, record_key and aggregate_id stored,
 *     key_bucket the derivation, published_at NULL and created_at the write time
 * </pre>
 */
@Tag("container")
@Testcontainers
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxWriterContainerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IngestTestImages.POSTGRES);

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    private static final UUID MESSAGE_ID = UUID.fromString("f0000000-0000-4000-8000-000000000001");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("insert into public.tenant (id, code, status) values (:id::uuid, 'writer-a', 'ACTIVE')")
                .param("id", TENANT_A.toString())
                .update();
    }

    private JdbcOutboxWriter writerForTenantA() {
        return new JdbcOutboxWriter(jdbcClient, () -> Optional.of(TENANT_A));
    }

    private static OutboxEntry entry(String recordKey) {
        return new OutboxEntry(OutboxTopic.MESSAGE_ACCEPTED, MESSAGE_ID, recordKey, "00-trace-span-01", null);
    }

    private long committedAppend(OutboxEntry entry) {
        Long id = txTemplate.execute(status -> writerForTenantA().append(entry));
        return id;
    }

    private int outboxRowCount() {
        Integer count = jdbcClient.sql("select count(*) from public.outbox").query(Integer.class).single();
        return count;
    }

    @Test
    void appendRollsBackWithTheCallersTransactionWhenTheCallerDies() {
        txTemplate.execute(status -> {
            writerForTenantA().append(entry("tenant-a/order-kill"));
            status.setRollbackOnly(); // kill the transaction after the append (E2-F1-S1 "kill the caller")
            return null;
        });

        assertThat(outboxRowCount()).as("a rolled-back intake leaves no outbox row").isZero();

        committedAppend(entry("tenant-a/order-alive"));
        assertThat(outboxRowCount()).as("the same append inside a committing caller is stored").isEqualTo(1);
    }

    @Test
    void appendWritesEveryColumnAndReturnsTheNewId() {
        long id = committedAppend(entry("tenant-a/order-1"));

        Map<String, Object> row = jdbcClient.sql("""
                select tenant_id, topic, aggregate_id, record_key, key_bucket, traceparent, available_at,
                       created_at, published_at
                from public.outbox where id = :id""")
                .param("id", id)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "tenant_id", rs.getObject("tenant_id"),
                        "topic", rs.getString("topic"),
                        "aggregate_id", rs.getObject("aggregate_id"),
                        "record_key", rs.getString("record_key"),
                        "key_bucket", rs.getInt("key_bucket"),
                        "traceparent", rs.getString("traceparent"),
                        "available_at", rs.getObject("available_at", OffsetDateTime.class),
                        "created_at", rs.getObject("created_at", OffsetDateTime.class),
                        "published_at", rs.getObject("published_at", OffsetDateTime.class)))
                .single();

        assertThat(UUID.fromString(row.get("tenant_id").toString())).isEqualTo(TENANT_A);
        assertThat(row.get("topic")).isEqualTo(OutboxTopic.MESSAGE_ACCEPTED.topicName());
        assertThat(UUID.fromString(row.get("aggregate_id").toString())).isEqualTo(MESSAGE_ID);
        assertThat(row.get("record_key")).isEqualTo("tenant-a/order-1");
        assertThat((Integer) row.get("key_bucket")).isEqualTo(KeyBuckets.of("tenant-a/order-1"));
        assertThat(row.get("traceparent")).isEqualTo("00-trace-span-01");
        assertThat(row.get("published_at")).as("published_at is NULL on write").isNull();
        assertThat((OffsetDateTime) row.get("created_at")).isNotNull();
        assertThat(((OffsetDateTime) row.get("available_at")).toInstant())
                .as("null availableAt defaults to write time (relay sees it due immediately)")
                .isBetween(Instant.now().minus(2, ChronoUnit.MINUTES), Instant.now().plus(2, ChronoUnit.MINUTES));
    }

    @Test
    void returnedIdsAscendInInsertOrder() {
        long first = committedAppend(entry("tenant-a/asc-1"));
        long second = committedAppend(entry("tenant-a/asc-2"));
        long third = committedAppend(entry("tenant-a/asc-3"));

        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(third);
    }

    @Test
    void deferredEntryStoresItsAvailableAtAndNullTraceparentSurvives() {
        Instant due = Instant.parse("2099-01-02T03:04:05Z");
        long id = committedAppend(new OutboxEntry(OutboxTopic.DELIVERY_DUE, MESSAGE_ID, "tenant-a/deferred", null,
                due));

        OffsetDateTime availableAt = jdbcClient.sql("select available_at from public.outbox where id = :id")
                .param("id", id)
                .query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class))
                .single();
        String traceparent = jdbcClient.sql("select traceparent from public.outbox where id = :id")
                .param("id", id)
                .query((rs, rowNum) -> rs.getString(1))
                .single();

        assertThat(availableAt.toInstant()).isEqualTo(due);
        assertThat(traceparent).isNull();
    }

    @Test
    void theMigrationCheckStillOwnsTheTopicRegister() {
        long accepted = committedAppend(entry("tenant-a/topic-1"));
        txTemplate.execute(status -> writerForTenantA().append(
                new OutboxEntry(OutboxTopic.DELIVERY_DUE, MESSAGE_ID, "tenant-a/topic-2", null, null)));
        assertThat(accepted).isPositive();
        assertThat(outboxRowCount()).isEqualTo(2);

        // Only the two seam topics pass the DB CHECK - the writer's enum cannot be widened behind it (R10).
        assertThatThrownBy(() -> jdbcClient.sql("""
                insert into public.outbox (tenant_id, topic, aggregate_id, record_key, key_bucket)
                values (:tenant_id::uuid, 'platform.unknown', :aggregate_id::uuid, 'raw', 0)""")
                .params(Map.of("tenant_id", TENANT_A.toString(), "aggregate_id", MESSAGE_ID.toString()))
                .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("outbox_topic_check");
    }

    @Test
    void keyBucketIsStoredInRangeForEveryRowAndMatchesTheDerivation() {
        for (int i = 0; i < 200; i++) {
            final int n = i;
            txTemplate.execute(status -> writerForTenantA()
                    .append(entry("bulk/interface-9/business-key-" + n + "@source-1")));
        }

        Map<String, Integer> stored = new java.util.HashMap<>();
        jdbcClient.sql("select record_key, key_bucket from public.outbox")
                .query((rs, rowNum) -> stored.put(rs.getString("record_key"), rs.getInt("key_bucket")))
                .list();

        assertThat(stored).hasSize(200);
        stored.forEach((recordKey, bucket) -> {
            assertThat(bucket).as("CHECK 0..63 holds for %s", recordKey).isBetween(0, 63);
            assertThat(bucket).as("stored bucket equals the sha-256 derivation for %s", recordKey)
                    .isEqualTo(KeyBuckets.of(recordKey));
        });
    }

    @Test
    void theMigrationCheckStillOwnsTheBucketRange() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                insert into public.outbox (tenant_id, topic, aggregate_id, record_key, key_bucket)
                values (:tenant_id::uuid, 'platform.message.accepted', :aggregate_id::uuid, 'raw', 64)""")
                .params(Map.of("tenant_id", TENANT_A.toString(), "aggregate_id", MESSAGE_ID.toString()))
                .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("outbox_key_bucket_check");
    }
}
