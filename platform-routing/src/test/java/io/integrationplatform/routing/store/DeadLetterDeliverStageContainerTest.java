package io.integrationplatform.routing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.delivery.internal.stage1.JdbcStage1Deliveries;
import io.integrationplatform.delivery.port.Stage1Delivery;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.internal.timing.JdbcStageTimingWriter;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Lane deliver-deadletter (E13 FAILED on DELIVER, E18 four-stage timeline) on the real message-store family: the two
 * dead letters that do NOT go through the send-outcome recorder - the credential dead letter (T-185, named reason) and
 * the reconciler's AMBIGUOUS stale-claim dead letter - each write one DELIVER row at the move's own instant through
 * {@link JdbcDeliveryRetryStore} built with the stage timing writer, in the caller's stamped transaction; a repeat
 * writes nothing, a rollback leaves nothing, an overtaken claim moves nothing, and the tenant is only the stamp's.
 * Sibling of {@link StageTimingCommitContainerTest} (same clean-room login, same seeding); AUTHORED, NOT RUN here -
 * the Docker lock was held by QA when this lane built it.
 *
 * <pre>
 * Scenario: The credential dead letter writes one DELIVER row at the move instant; a replay writes none
 * Scenario: The reconciler AMBIGUOUS dead letter writes one DELIVER row at the move instant
 * Scenario: A rolled-back dead letter leaves no DELIVER row
 * Scenario: An overtaken claim moves nothing and writes no DELIVER row
 * Scenario: The tenant comes only from the stamped context
 * Scenario: A four-stage timeline INTAKE, ROUTE, TRANSFORM, DELIVER holds for the dead-letter outcome
 * </pre>
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class DeadLetterDeliverStageContainerTest {

    private static final UUID TENANT_A = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000dead1");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000dead2");
    private static final UUID TARGET = UUID.fromString("dddddddd-0000-0000-0000-00000000dead");
    private static final String REASON = "target credential unreadable";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");

    private DataSource owner;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;

    @BeforeAll
    void migrateAndSeed() throws SQLException {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new SimpleDriverDataSource(new org.postgresql.Driver(), POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        IntakeStoreAppRole.Login login = IntakeStoreAppRole.grantAppRoleLogin(POSTGRES);
        DataSource app = new SimpleDriverDataSource(new org.postgresql.Driver(), POSTGRES.getJdbcUrl(),
                login.username(), login.password());
        jdbc = JdbcClient.create(app);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(app));
        ownerUpdate("insert into public.tenant (id, code, status, time_zone) values"
                + " (?, 'deadletter-a', 'ACTIVE', 'Etc/UTC'), (?, 'deadletter-b', 'ACTIVE', 'Etc/UTC')",
                TENANT_A, TENANT_B);
    }

    @Test
    @DisplayName("the credential dead letter writes one DELIVER row at the move instant; a replay writes none")
    void theCredentialDeadLetterWritesOneDeliverRowAndAReplayNone() throws SQLException {
        Seeded seeded = routedAndClaimed(TENANT_A);
        Instant before = micros(Instant.now());

        assertThat(inTx(TENANT_A, () -> store(TENANT_A).deadLetter(seeded.delivery, seeded.token,
                OutcomeClass.PERMANENT, REASON))).isTrue();
        Instant after = micros(Instant.now()).plusMillis(1);
        // the same dead letter again: the delivery is DEAD_LETTERED already, 0 rows, nothing recorded
        assertThat(inTx(TENANT_A, () -> store(TENANT_A).deadLetter(seeded.delivery, seeded.token,
                OutcomeClass.PERMANENT, REASON))).isFalse();

        assertThat(deliveryState(seeded.delivery)).isEqualTo("DEAD_LETTERED");
        List<StageRow> rows = stages(seeded.message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");
        assertThat(rows.get(1).finishedAt()).as("FAILED records on DELIVER, finished at the move")
                .isBetween(before, after);
        assertThat(rows.get(1).startedAt()).isBeforeOrEqualTo(rows.get(1).finishedAt());
    }

    @Test
    @DisplayName("the reconciler AMBIGUOUS dead letter writes one DELIVER row at the move instant")
    void theReconcilerAmbiguousDeadLetterWritesOneDeliverRow() throws SQLException {
        Seeded seeded = routedAndClaimed(TENANT_A);
        Instant before = micros(Instant.now());

        assertThat(inTx(TENANT_A, () -> store(TENANT_A).deadLetter(seeded.delivery, seeded.token,
                OutcomeClass.AMBIGUOUS))).isTrue();
        Instant after = micros(Instant.now()).plusMillis(1);

        assertThat(deliveryState(seeded.delivery)).isEqualTo("DEAD_LETTERED");
        List<StageRow> rows = stages(seeded.message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");
        assertThat(rows.get(1).finishedAt()).isBetween(before, after);
    }

    @Test
    @DisplayName("a rolled-back dead letter leaves no DELIVER row")
    void aRolledBackDeadLetterLeavesNoDeliverRow() throws SQLException {
        Seeded seeded = routedAndClaimed(TENANT_A);

        assertThatThrownBy(() -> inTx(TENANT_A, () -> {
            store(TENANT_A).deadLetter(seeded.delivery, seeded.token, OutcomeClass.PERMANENT, REASON);
            assertThat(timingRowsInTx(seeded.message, "DELIVER")).as("the row is this transaction's own")
                    .isEqualTo(1);
            throw new IllegalStateException("the caller fails after the move");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(deliveryState(seeded.delivery)).isEqualTo("SENDING");
        assertThat(stages(seeded.message)).extracting(StageRow::stage).containsExactly("ROUTE");
    }

    @Test
    @DisplayName("an overtaken claim moves nothing and writes no DELIVER row")
    void anOvertakenClaimMovesNothingAndWritesNoDeliverRow() throws SQLException {
        Seeded seeded = routedAndClaimed(TENANT_A);

        assertThat(inTx(TENANT_A, () -> store(TENANT_A).deadLetter(seeded.delivery, UUID.randomUUID(),
                OutcomeClass.AMBIGUOUS))).isFalse();
        assertThat(inTx(TENANT_A, () -> store(TENANT_A).deadLetter(seeded.delivery, UUID.randomUUID(),
                OutcomeClass.PERMANENT, REASON))).isFalse();

        assertThat(deliveryState(seeded.delivery)).isEqualTo("SENDING");
        assertThat(stages(seeded.message)).extracting(StageRow::stage).containsExactly("ROUTE");
    }

    @Test
    @DisplayName("the tenant comes only from the stamped context")
    void theTenantComesOnlyFromTheStampedContext() throws SQLException {
        Seeded seeded = routedAndClaimed(TENANT_A);

        // a tenant-B stamp reaching for tenant A's delivery moves nothing and writes nothing
        assertThat(inTx(TENANT_B, () -> store(TENANT_B).deadLetter(seeded.delivery, seeded.token,
                OutcomeClass.PERMANENT, REASON))).isFalse();
        assertThat(deliveryState(seeded.delivery)).isEqualTo("SENDING");
        assertThat(stages(seeded.message)).extracting(StageRow::stage).containsExactly("ROUTE");

        assertThat(inTx(TENANT_A, () -> store(TENANT_A).deadLetter(seeded.delivery, seeded.token,
                OutcomeClass.PERMANENT, REASON))).isTrue();
        assertThat(stages(seeded.message)).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");
        assertThat(ownerCount("select count(*) from public.message_stage_timing where tenant_id = ?", TENANT_B))
                .as("nothing landed on tenant B").isZero();
    }

    @Test
    @DisplayName("a four-stage timeline INTAKE, ROUTE, TRANSFORM, DELIVER holds for the dead-letter outcome")
    void aFourStageTimelineHoldsForTheDeadLetterOutcome() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);
        runInTx(TENANT_A, () -> writer(TENANT_A).record(message,
                io.integrationplatform.ingest.port.StageTimings.Stage.INTAKE, acceptedAt, acceptedAt.plusMillis(5)));
        route(TENANT_A, message, acceptedAt, UUID.randomUUID());
        runInTx(TENANT_A, () -> writer(TENANT_A).record(message,
                io.integrationplatform.ingest.port.StageTimings.Stage.TRANSFORM, acceptedAt.plusMillis(10),
                acceptedAt.plusMillis(20)));
        UUID delivery = deliveries(message).get(0);
        UUID token = claim(TENANT_A, delivery);

        runInTx(TENANT_A, () -> store(TENANT_A).deadLetter(delivery, token, OutcomeClass.PERMANENT, REASON));

        List<StageRow> rows = stages(message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("INTAKE", "ROUTE", "TRANSFORM", "DELIVER");
        assertThat(rows).allSatisfy(row -> assertThat(row.finishedAt()).isAfterOrEqualTo(row.startedAt()));
    }

    // ---------------------------------------------------------------- the production wiring under proof

    private JdbcDeliveryRetryStore store(UUID tenant) {
        return new JdbcDeliveryRetryStore(jdbc, context(tenant), writer(tenant));
    }

    private JdbcStageTimingWriter writer(UUID tenant) {
        return new JdbcStageTimingWriter(jdbc, context(tenant));
    }

    private JdbcDeliveryRepository repository(UUID tenant) {
        return new JdbcDeliveryRepository(jdbc, context(tenant));
    }

    private static TenantContext context(UUID tenant) {
        return () -> Optional.of(tenant);
    }

    private record Seeded(UUID message, UUID delivery, UUID token) {
    }

    private Seeded routedAndClaimed(UUID tenant) throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(tenant, acceptedAt);
        route(tenant, message, acceptedAt, UUID.randomUUID());
        UUID delivery = deliveries(message).get(0);
        return new Seeded(message, delivery, claim(tenant, delivery));
    }

    /** Stage 1's one transaction, as the stage-1 consumer runs it: the fan-out insert, then the guard. */
    private boolean route(UUID tenant, UUID message, Instant acceptedAt, UUID route) {
        return inTx(tenant, () -> {
            JdbcStage1Deliveries stage1 = new JdbcStage1Deliveries(repository(tenant), writer(tenant));
            stage1.insertPerRoute(List.of(
                    new Stage1Delivery(message, route, acceptedAt, TARGET, null, null, null, null, null, null)));
            return stage1.markMessageRouted(message);
        });
    }

    private UUID claim(UUID tenant, UUID delivery) {
        UUID token = UUID.randomUUID();
        assertThat(inTx(tenant, () -> repository(tenant).claimSend(delivery, token, Instant.now())))
                .as("PENDING/DUE -> SENDING").isTrue();
        return token;
    }

    /** One ipaas_app transaction stamped for the tenant ({@code set_config} local, R11) - the production shape. */
    private <T> T inTx(UUID tenant, Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.sql("select set_config('app.tenant_id', :tenant, true)").param("tenant", tenant.toString())
                    .query().singleValue();
            return work.get();
        });
    }

    private void runInTx(UUID tenant, Runnable work) {
        inTx(tenant, () -> {
            work.run();
            return null;
        });
    }

    private long timingRowsInTx(UUID message, String stage) {
        return jdbc.sql("select count(*) from public.message_stage_timing where message_id = :message::uuid"
                        + " and stage = :stage")
                .param("message", message.toString()).param("stage", stage).query(Long.class).single();
    }

    // ---------------------------------------------------------------- owner-side seeding and reads (RLS bypassed)

    private record StageRow(String stage, Instant startedAt, Instant finishedAt) {
    }

    private UUID message(UUID tenant, Instant acceptedAt) throws SQLException {
        UUID id = UUID.randomUUID();
        ownerUpdate("insert into public.message (id, tenant_id, interface_id, interface_version_id,"
                        + " source_contract_version_id, business_key, accepted_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "dead-letter-" + id,
                at(acceptedAt), at(acceptedAt));
        return id;
    }

    /** Every timing row of the message, in pipeline order. */
    private List<StageRow> stages(UUID message) throws SQLException {
        try (Connection read = owner.getConnection(); PreparedStatement query = read.prepareStatement(
                "select stage, started_at, finished_at from public.message_stage_timing where message_id = ?"
                        + " order by array_position(array['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER'], stage)")) {
            query.setObject(1, message);
            List<StageRow> rows = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    OffsetDateTime finished = result.getObject(3, OffsetDateTime.class);
                    rows.add(new StageRow(result.getString(1), result.getObject(2, OffsetDateTime.class).toInstant(),
                            finished == null ? null : finished.toInstant()));
                }
            }
            return rows;
        }
    }

    private List<UUID> deliveries(UUID message) throws SQLException {
        try (Connection read = owner.getConnection(); PreparedStatement query = read.prepareStatement(
                "select id from public.delivery where message_id = ? order by route_id")) {
            query.setObject(1, message);
            List<UUID> ids = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getObject(1, UUID.class));
                }
            }
            return ids;
        }
    }

    private String deliveryState(UUID delivery) throws SQLException {
        try (Connection read = owner.getConnection(); PreparedStatement query = read.prepareStatement(
                "select state from public.delivery where id = ?")) {
            query.setObject(1, delivery);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private long ownerCount(String sql, Object parameter) throws SQLException {
        try (Connection read = owner.getConnection(); PreparedStatement query = read.prepareStatement(sql)) {
            query.setObject(1, parameter);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void ownerUpdate(String sql, Object... parameters) throws SQLException {
        try (Connection write = owner.getConnection(); PreparedStatement statement = write.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
