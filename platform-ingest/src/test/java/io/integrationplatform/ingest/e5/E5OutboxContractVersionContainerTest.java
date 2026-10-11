package io.integrationplatform.ingest.e5;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.ingest.internal.outbox.IngestTestImages;
import io.integrationplatform.ingest.internal.outbox.JdbcOutboxWriter;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E5-F2-S1 (T-106), the ingest seam of "Every message records its contract version" (ADR-011 Decision 3 rule 3): the
 * outbox row a caller appends inside its intake or routing transaction carries the contract version that caller
 * resolved, in an additive nullable {@code contract_version_id} column (V2_1_2). An entry written the E2 way (no
 * version) still stores, with the column NULL - the change is additive. Container group (R03), PostgreSQL only; the
 * same clean-room shape as {@code OutboxWriterContainerTest} (own DataSource, own transaction manager, own Flyway run).
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class E5OutboxContractVersionContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IngestTestImages.POSTGRES);

    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e5");
    private static final UUID CONTRACT_V2 = UUID.fromString("c2000000-0000-4000-8000-0000000000e5");

    private JdbcClient jdbc;
    private TransactionTemplate tx;

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
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("insert into public.tenant (id, code, status) values (:id::uuid, 'e5-outbox-b', 'ACTIVE')")
                .param("id", TENANT_B.toString())
                .update();
    }

    private long append(OutboxEntry entry) {
        JdbcOutboxWriter writer = new JdbcOutboxWriter(jdbc, () -> Optional.of(TENANT_B));
        return tx.execute(status -> writer.append(entry));
    }

    private String storedContractVersion(long outboxId) {
        return jdbc.sql("select coalesce(contract_version_id::text, '-') from public.outbox where id = :id")
                .param("id", outboxId)
                .query(String.class)
                .single();
    }

    @Test
    @DisplayName("Scenario: Every message records its contract version - the outbox row names the version it was given")
    void theOutboxRowRecordsTheContractVersionTheCallerResolved() {
        UUID deliveryId = UUID.randomUUID();

        long id = append(new OutboxEntry(OutboxTopic.DELIVERY_DUE, deliveryId, "target-b/order-1", null, null,
                CONTRACT_V2));

        assertThat(storedContractVersion(id)).isEqualTo(CONTRACT_V2.toString());
    }

    @Test
    @DisplayName("Additive: an entry written without a contract version still stores, with the column NULL")
    void anEntryWithoutAVersionStillStores() {
        long id = append(new OutboxEntry(OutboxTopic.MESSAGE_ACCEPTED, UUID.randomUUID(), "target-b/order-2", null,
                null));

        assertThat(storedContractVersion(id)).isEqualTo("-");
    }

    @Test
    @DisplayName("The column is an additive, nullable uuid on public.outbox (V2_1_2)")
    void theColumnIsAnAdditiveNullableUuid() {
        String shape = jdbc.sql("select data_type || '|' || is_nullable from information_schema.columns"
                        + " where table_schema = 'public' and table_name = 'outbox'"
                        + " and column_name = 'contract_version_id'")
                .query(String.class)
                .optional()
                .orElse("missing");

        assertThat(shape).isEqualTo("uuid|YES");
    }
}
