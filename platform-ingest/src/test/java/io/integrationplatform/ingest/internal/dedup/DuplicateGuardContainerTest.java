package io.integrationplatform.ingest.internal.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.ingest.internal.outbox.IngestTestImages;
import io.integrationplatform.ingest.internal.outbox.JdbcOutboxWriter;
import io.integrationplatform.ingest.port.OutboundRecord;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.postgresql.Driver;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T-010 (E2-F1-S1): the consumer-side duplicate guard over the idempotency key of {@link OutboundRecord}
 * ("outbox:&lt;row-id&gt;") against real PostgreSQL. Clean-room on purpose - own {@link DataSource}, own transaction
 * manager, programmatic Flyway, no slice context (same reason as documented on the outbox container tests). A claim
 * is made inside the CONSUMER's transaction, so it commits with the offset work and rolls back when the consumer
 * dies, exactly like the outbox append on the producer side.
 *
 * <pre>
 * Scenario: First record passes, re-published record is flagged duplicate (E2-F1-S1 @negative)
 *   Given an outbox row appended and committed by the T-007 writer (crash after publish, before the mark)
 *   When the consumer claims its key once, and the same key again after the re-publish
 *   Then the first claim passes and the second is flagged duplicate, one claim row only
 *
 * Scenario: A rolled-back claim is no claim
 *   When the consumer transaction dies after the claim and another consumer tries the same key
 *   Then it still passes - the duplicate detection only sees committed claims
 *
 * Scenario: Every outbox row has its own key
 *   When two rows' keys are claimed
 *   Then both pass and both claim rows exist
 * </pre>
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class DuplicateGuardContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IngestTestImages.POSTGRES);

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    private static final UUID MESSAGE_ID = UUID.fromString("f0000000-0000-4000-8000-000000000001");

    private JdbcClient jdbc;
    private TransactionTemplate txTemplate;

    @BeforeAll
    void migrateAndSeed() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DataSource dataSource = new SimpleDriverDataSource(
                new Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("insert into public.tenant (id, code, status) values (:id::uuid, 'dedup-a', 'ACTIVE')")
                .param("id", TENANT_A.toString())
                .update();
    }

    private JdbcDuplicateGuard guardForTenantA() {
        return new JdbcDuplicateGuard(jdbc, () -> Optional.of(TENANT_A));
    }

    /** The producer side for real: T-007's writer appends a row and the intake transaction commits it. */
    private OutboundRecord publishedOutboxRow(String recordKey) {
        Long id = txTemplate.execute(status -> new JdbcOutboxWriter(jdbc, () -> Optional.of(TENANT_A))
                .append(new OutboxEntry(OutboxTopic.MESSAGE_ACCEPTED, MESSAGE_ID, recordKey, "00-trace-01", null)));
        return new OutboundRecord(OutboxTopic.MESSAGE_ACCEPTED.topicName(), recordKey, id, MESSAGE_ID, "00-trace-01");
    }

    private boolean claimInsideConsumerTx(String idempotencyKey) {
        Boolean duplicate = txTemplate.execute(status -> guardForTenantA().isDuplicate(idempotencyKey));
        return duplicate;
    }

    private int claimRowCountFor(String idempotencyKey) {
        return jdbc.sql("select count(*) from public.idempotency_key where idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(Integer.class).single();
    }

    @Test
    void firstRecordPassesAndTheCrashRepublishedRecordIsFlaggedDuplicate() {
        OutboundRecord record = publishedOutboxRow("dedup/crash-republish");

        assertThat(claimInsideConsumerTx(record.idempotencyKey()))
                .as("consumer one sees the record for the first time").isFalse();
        // The publisher crashed after publish and before the mark: the relay re-publishes the SAME row, so the
        // re-delivered record carries the same idempotency key (OutboundRecord.idempotencyKey is row-stable).
        OutboundRecord republished = new OutboundRecord(record.topic(), record.key(), record.outboxId(),
                record.aggregateId(), record.traceparent());

        assertThat(claimInsideConsumerTx(republished.idempotencyKey()))
                .as("the re-published record is detected (E2-F1-S1)").isTrue();
        assertThat(claimRowCountFor(record.idempotencyKey()))
                .as("detection never doubles the claim").isEqualTo(1);
    }

    @Test
    void aRolledBackConsumerClaimIsNoClaimForTheNextConsumer() {
        OutboundRecord record = publishedOutboxRow("dedup/rolled-back-claim");
        txTemplate.execute(status -> {
            guardForTenantA().isDuplicate(record.idempotencyKey());
            status.setRollbackOnly(); // the consumer dies after claiming but before committing
            return null;
        });

        assertThat(claimRowCountFor(record.idempotencyKey())).as("the claim rolled back with the consumer").isZero();
        assertThat(claimInsideConsumerTx(record.idempotencyKey()))
                .as("the next consumer of the same record still passes").isFalse();
    }

    @Test
    void differentOutboxRowsClaimDifferentKeysAndBothPass() {
        OutboundRecord first = publishedOutboxRow("dedup/row-1");
        OutboundRecord second = publishedOutboxRow("dedup/row-2");

        assertThat(claimInsideConsumerTx(first.idempotencyKey())).isFalse();
        assertThat(claimInsideConsumerTx(second.idempotencyKey())).isFalse();
        assertThat(claimRowCountFor(first.idempotencyKey())).isEqualTo(1);
        assertThat(claimRowCountFor(second.idempotencyKey())).isEqualTo(1);
        assertThat(first.idempotencyKey()).isNotEqualTo(second.idempotencyKey());
    }

    @Test
    void theUniqueIndexIsWhatRefusesASecondClaimRowForTheSameKey() {
        publishedOutboxRow("dedup/raw-unique");
        String key = "outbox:" + jdbc.sql("select max(id) from public.outbox where record_key = :k")
                .param("k", "dedup/raw-unique").query(Long.class).single();
        jdbc.sql("insert into public.idempotency_key (tenant_id, idempotency_key) values (:t::uuid, :k)")
                .params(java.util.Map.of("t", TENANT_A.toString(), "k", key))
                .update();

        assertThatThrownBy(() -> jdbc.sql(
                "insert into public.idempotency_key (tenant_id, idempotency_key) values (:t::uuid, :k)")
                .params(java.util.Map.of("t", TENANT_A.toString(), "k", key))
                .update())
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void theMigrationCheckStillOwnsTheKeyRegister() {
        // Closed shape of the key column is the migration's (text + CHECK, never ENUM - R10): a blank key is refused
        // by the database itself, whatever any application code promised.
        assertThatThrownBy(() -> jdbc.sql(
                "insert into public.idempotency_key (tenant_id, idempotency_key) values (:t::uuid, '')")
                .param("t", TENANT_A.toString())
                .update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause()
                .hasMessageContaining("check");
    }
}
