package io.integrationplatform.routing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcSendOutcomes;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.delivery.internal.retry.OutcomeRecorder;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.delivery.internal.stage1.JdbcStage1Deliveries;
import io.integrationplatform.delivery.port.Stage1Delivery;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.internal.timing.JdbcStageTimingWriter;
import io.integrationplatform.ingest.port.StageTimings;
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
import java.util.Arrays;
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
 * T105 (T-136 data side, E13-F1-S1) - the stage timing rows of the later stages on the real message-store family
 * (V2_1_1110, ADR-014), written at each stage's commit point in the same transaction as its state change:
 * ROUTED is the {@code ACCEPTED -> ROUTED} guard of stage 1 (one ROUTE row), FAILED is the outcome recorder's dead
 * letter (DELIVER, finished at the failure), DELIVERED is the success move followed by the same DELIVER writer.
 * Lives in platform-routing for the {@link IntakeCommitContainerTest} reason: only this module's test classpath
 * carries every family's migrations (message and timing here, delivery, ingest outbox, tenancy).
 *
 * <p>Clean-room like {@link IntakeCommitContainerTest}: every write runs as the restricted {@code ipaas_app} login
 * inside an explicitly stamped transaction; the owner (superuser) connection only seeds and reads.
 *
 * <pre>
 * Scenario: ROUTED writes one ROUTE row with the flip; a re-consume writes none
 * Scenario: A rolled-back stage 1 leaves no ROUTE row
 * Scenario: A dead letter writes one DELIVER row, started at the first call, finished at the failure
 * Scenario: A rolled-back dead letter leaves no DELIVER row
 * Scenario: A scheduled retry is not a finish - no DELIVER row
 * Scenario: The DELIVERED move writes one DELIVER row through the same writer
 * Scenario: A fan-out writes one DELIVER row when its last delivery is final, and never raises
 * Scenario: The tenant comes only from the stamped context
 * Scenario: Per message the rows are ordered and no duration is negative
 * Scenario: A delivery without a stage-1 message dead-letters without a row and without an error
 * Scenario: A DELIVER row never starts after it finishes
 * </pre>
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class StageTimingCommitContainerTest {

    private static final UUID TENANT_A = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000105aa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000105bb");
    private static final UUID TARGET = UUID.fromString("dddddddd-0000-0000-0000-000000000105");

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
                + " (?, 'timing-a', 'ACTIVE', 'Etc/UTC'), (?, 'timing-b', 'ACTIVE', 'Etc/UTC')", TENANT_A, TENANT_B);
    }

    // ---------------------------------------------------------------- ROUTED -> ROUTE

    @Test
    @DisplayName("ROUTED writes one ROUTE row with the flip; a re-consume writes none")
    void routedWritesOneRouteRowAndAReconsumeWritesNone() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);

        assertThat(route(TENANT_A, message, acceptedAt, UUID.randomUUID())).as("the guard flipped").isTrue();
        List<StageRow> rows = stages(message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("ROUTE");
        assertThat(rows.get(0).finishedAt()).as("the ROUTE row is finished").isNotNull();
        assertThat(rows.get(0).finishedAt()).isAfterOrEqualTo(rows.get(0).startedAt());

        // a crash or a rebalance redelivers message.accepted: the guard is a no-op, and so is the timing
        assertThat(inTx(TENANT_A, () -> stage1(TENANT_A).markMessageRouted(message))).isFalse();
        assertThat(stages(message)).extracting(StageRow::stage).containsExactly("ROUTE");
    }

    @Test
    @DisplayName("a rolled-back stage 1 leaves no ROUTE row")
    void aRolledBackRoutingLeavesNoRouteRow() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);

        assertThatThrownBy(() -> runInTx(TENANT_A, () -> {
            JdbcStage1Deliveries stage1 = stage1(TENANT_A);
            stage1.insertPerRoute(List.of(delivery(message, acceptedAt, UUID.randomUUID())));
            assertThat(stage1.markMessageRouted(message)).isTrue();
            assertThat(timingRowsInTx(message, "ROUTE")).as("the ROUTE row is the flip's own transaction's")
                    .isEqualTo(1);
            throw new IllegalStateException("stage 1 fails after the flip");
        })).isInstanceOf(IllegalStateException.class).hasMessage("stage 1 fails after the flip");

        assertThat(messageState(message)).isEqualTo("ACCEPTED");
        assertThat(stages(message)).as("a rollback leaves none").isEmpty();
    }

    // ---------------------------------------------------------------- FAILED / DELIVERED -> DELIVER

    @Test
    @DisplayName("a dead letter writes one DELIVER row, started at the first call, finished at the failure")
    void aDeadLetterWritesOneDeliverRowFinishedAtTheFailure() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);
        route(TENANT_A, message, acceptedAt, UUID.randomUUID());
        UUID delivery = deliveries(message).get(0);
        UUID token = claim(TENANT_A, delivery);
        Instant callStarted = micros(Instant.now());
        attempt(TENANT_A, delivery, OutcomeClass.PERMANENT, callStarted, callStarted.plusMillis(40));
        Instant failedAt = callStarted.plusMillis(45);

        runInTx(TENANT_A, () -> recorder(TENANT_A).record(delivery, token, 1, OutcomeClass.PERMANENT, failedAt,
                null, false));

        assertThat(deliveryState(delivery)).isEqualTo("DEAD_LETTERED");
        List<StageRow> rows = stages(message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");
        assertThat(rows.get(1).startedAt()).as("DELIVER starts at the first call").isEqualTo(callStarted);
        assertThat(rows.get(1).finishedAt()).as("FAILED records on DELIVER, finished at the failure")
                .isEqualTo(failedAt);
    }

    @Test
    @DisplayName("a rolled-back dead letter leaves no DELIVER row")
    void aRolledBackDeadLetterLeavesNoDeliverRow() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);
        route(TENANT_A, message, acceptedAt, UUID.randomUUID());
        UUID delivery = deliveries(message).get(0);
        UUID token = claim(TENANT_A, delivery);

        assertThatThrownBy(() -> runInTx(TENANT_A, () -> {
            recorder(TENANT_A).record(delivery, token, 1, OutcomeClass.PERMANENT, micros(Instant.now()), null,
                    false);
            assertThat(timingRowsInTx(message, "DELIVER")).as("the DELIVER row is the dead letter's own"
                    + " transaction's").isEqualTo(1);
            throw new IllegalStateException("the attempt executor fails after the outcome");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the attempt executor fails after the outcome");

        assertThat(deliveryState(delivery)).isEqualTo("SENDING");
        assertThat(stages(message)).as("a rollback leaves none").extracting(StageRow::stage)
                .containsExactly("ROUTE");
    }

    @Test
    @DisplayName("a scheduled retry is not a finish - no DELIVER row")
    void aScheduledRetryWritesNoDeliverRow() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);
        route(TENANT_A, message, acceptedAt, UUID.randomUUID());
        UUID delivery = deliveries(message).get(0);
        UUID token = claim(TENANT_A, delivery);

        runInTx(TENANT_A, () -> recorder(TENANT_A).record(delivery, token, 1, OutcomeClass.TRANSIENT,
                micros(Instant.now()), null, false));

        assertThat(deliveryState(delivery)).isEqualTo("RETRY_WAIT");
        assertThat(stages(message)).extracting(StageRow::stage).containsExactly("ROUTE");
    }

    @Test
    @DisplayName("the send path's markDelivered writes one DELIVER row, a redelivery writes none")
    void theSendOutcomesDeliveredMoveWritesOneDeliverRowAndARedeliveryNone() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);
        route(TENANT_A, message, acceptedAt, UUID.randomUUID());
        UUID delivery = deliveries(message).get(0);
        UUID token = claim(TENANT_A, delivery);
        Instant callStarted = micros(Instant.now());
        attempt(TENANT_A, delivery, OutcomeClass.SUCCESS, callStarted, callStarted.plusMillis(30));
        JdbcSendOutcomes outcomes = new JdbcSendOutcomes(jdbc, context(TENANT_A), writer(TENANT_A));

        assertThat(inTx(TENANT_A, () -> outcomes.markDelivered(delivery, token))).isTrue();
        // the broker redelivers the same due record: the delivery is DELIVERED already, so nothing moves or records
        assertThat(inTx(TENANT_A, () -> outcomes.markDelivered(delivery, token))).isFalse();

        assertThat(deliveryState(delivery)).isEqualTo("DELIVERED");
        List<StageRow> rows = stages(message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");
        assertThat(rows.get(1).startedAt()).isEqualTo(callStarted);
        assertThat(rows.get(1).finishedAt()).isAfterOrEqualTo(callStarted);
    }

    @Test
    @DisplayName("the DELIVERED move writes one DELIVER row through the same writer")
    void theDeliveredMoveWritesOneDeliverRow() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);
        route(TENANT_A, message, acceptedAt, UUID.randomUUID());
        UUID delivery = deliveries(message).get(0);
        UUID token = claim(TENANT_A, delivery);
        Instant callStarted = micros(Instant.now());
        attempt(TENANT_A, delivery, OutcomeClass.SUCCESS, callStarted, callStarted.plusMillis(30));
        Instant deliveredAt = callStarted.plusMillis(31);

        // the stage-2 success path's commit point: the SENDING -> DELIVERED move, then the writer, one transaction
        runInTx(TENANT_A, () -> {
            assertThat(repository(TENANT_A).markDelivered(delivery, token, deliveredAt)).isTrue();
            writer(TENANT_A).recordDeliverFinished(delivery, deliveredAt);
        });

        assertThat(deliveryState(delivery)).isEqualTo("DELIVERED");
        List<StageRow> rows = stages(message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");
        assertThat(rows.get(1).startedAt()).isEqualTo(callStarted);
        assertThat(rows.get(1).finishedAt()).isEqualTo(deliveredAt);
    }

    @Test
    @DisplayName("a fan-out writes one DELIVER row when its last delivery is final, and never raises")
    void aFanOutWritesOneDeliverRowWhenItsLastDeliveryIsFinal() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);
        route(TENANT_A, message, acceptedAt, UUID.randomUUID(), UUID.randomUUID());
        List<UUID> fanOut = deliveries(message);
        assertThat(fanOut).hasSize(2);
        UUID first = fanOut.get(0);
        UUID second = fanOut.get(1);
        UUID firstToken = claim(TENANT_A, first);
        UUID secondToken = claim(TENANT_A, second);

        runInTx(TENANT_A, () -> recorder(TENANT_A).record(first, firstToken, 1, OutcomeClass.PERMANENT,
                micros(Instant.now()), null, false));
        assertThat(deliveryState(first)).isEqualTo("DEAD_LETTERED");
        assertThat(stages(message)).as("a sibling is still open: the message's delivery stage runs on")
                .extracting(StageRow::stage).containsExactly("ROUTE");

        Instant deliveredAt = micros(Instant.now());
        runInTx(TENANT_A, () -> {
            assertThat(repository(TENANT_A).markDelivered(second, secondToken, deliveredAt)).isTrue();
            writer(TENANT_A).recordDeliverFinished(second, deliveredAt);
        });
        List<StageRow> rows = stages(message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");
        assertThat(rows.get(1).finishedAt()).as("the last final move closes the stage").isEqualTo(deliveredAt);

        // an operator replays the dead letter and it dies again: the row stands, and the move itself still commits
        assertThat(inTx(TENANT_A, () -> repository(TENANT_A).replay(first, Instant.now()))).isTrue();
        UUID replayToken = claim(TENANT_A, first);
        runInTx(TENANT_A, () -> recorder(TENANT_A).record(first, replayToken, 1, OutcomeClass.PERMANENT,
                micros(Instant.now()), null, false));
        assertThat(deliveryState(first)).isEqualTo("DEAD_LETTERED");
        assertThat(stages(message)).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");
        assertThat(stages(message).get(1).finishedAt()).isEqualTo(deliveredAt);
    }

    // ---------------------------------------------------------------- tenant, order, edges

    @Test
    @DisplayName("the tenant comes only from the stamped context")
    void theTenantComesOnlyFromTheStampedContext() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);
        route(TENANT_A, message, acceptedAt, UUID.randomUUID());
        UUID delivery = deliveries(message).get(0);
        UUID token = claim(TENANT_A, delivery);
        runInTx(TENANT_A, () -> recorder(TENANT_A).record(delivery, token, 1, OutcomeClass.PERMANENT,
                micros(Instant.now()), null, false));
        assertThat(stages(message)).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");

        assertThat(inTx(TENANT_B, () -> allTimingRowsInTx(message)))
                .as("a tenant-B session reads none of tenant A's rows").isZero();
        // a tenant-B stamp reaching for tenant A's work moves nothing and writes nothing
        UUID unrouted = message(TENANT_A, acceptedAt);
        assertThat(inTx(TENANT_B, () -> stage1(TENANT_B).markMessageRouted(unrouted))).isFalse();
        runInTx(TENANT_B, () -> writer(TENANT_B).recordDeliverFinished(delivery, micros(Instant.now())));
        assertThat(messageState(unrouted)).isEqualTo("ACCEPTED");
        assertThat(stages(unrouted)).isEmpty();
        assertThat(stages(message)).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");
        assertThat(ownerCount("select count(*) from public.message_stage_timing where tenant_id = ?", TENANT_B))
                .as("nothing landed on tenant B").isZero();
    }

    @Test
    @DisplayName("per message the rows are ordered and no duration is negative")
    void perMessageTheRowsAreOrderedAndNoDurationIsNegative() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);
        // INTAKE as the intake transaction writes it (IntakeService: started before the write, finished after)
        runInTx(TENANT_A, () -> writer(TENANT_A).record(message, StageTimings.Stage.INTAKE, acceptedAt,
                Instant.now()));
        route(TENANT_A, message, acceptedAt, UUID.randomUUID());
        UUID delivery = deliveries(message).get(0);
        UUID token = claim(TENANT_A, delivery);
        Instant callStarted = Instant.now();
        attempt(TENANT_A, delivery, OutcomeClass.PERMANENT, callStarted, Instant.now());
        runInTx(TENANT_A, () -> recorder(TENANT_A).record(delivery, token, 1, OutcomeClass.PERMANENT,
                Instant.now(), null, false));

        List<StageRow> rows = stages(message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("INTAKE", "ROUTE", "DELIVER");
        for (int i = 0; i < rows.size(); i++) {
            StageRow row = rows.get(i);
            assertThat(row.finishedAt()).as(row.stage() + " finished").isNotNull();
            assertThat(row.finishedAt()).as(row.stage() + " duration >= 0").isAfterOrEqualTo(row.startedAt());
            if (i > 0) {
                assertThat(row.startedAt()).as(row.stage() + " starts after " + rows.get(i - 1).stage() + " ends")
                        .isAfterOrEqualTo(rows.get(i - 1).finishedAt());
            }
        }
    }

    @Test
    @DisplayName("a delivery without a stage-1 message dead-letters without a row and without an error")
    void anUnpinnedDeliveryDeadLettersWithoutARowOrAnError() throws SQLException {
        UUID delivery = UUID.randomUUID();
        runInTx(TENANT_A, () -> repository(TENANT_A).create(delivery, UUID.randomUUID(), "t105-unpinned", null,
                TARGET, micros(Instant.now())));
        UUID token = claim(TENANT_A, delivery);
        long before = ownerCount("select count(*) from public.message_stage_timing where tenant_id = ?", TENANT_A);

        runInTx(TENANT_A, () -> recorder(TENANT_A).record(delivery, token, 1, OutcomeClass.PERMANENT,
                micros(Instant.now()), null, false));

        assertThat(deliveryState(delivery)).isEqualTo("DEAD_LETTERED");
        assertThat(ownerCount("select count(*) from public.message_stage_timing where tenant_id = ?", TENANT_A))
                .as("no message, no timeline row").isEqualTo(before);
    }

    @Test
    @DisplayName("a DELIVER row never starts after it finishes")
    void aDeliverRowNeverStartsAfterItFinishes() throws SQLException {
        Instant acceptedAt = micros(Instant.now());
        UUID message = message(TENANT_A, acceptedAt);
        route(TENANT_A, message, acceptedAt, UUID.randomUUID());
        UUID delivery = deliveries(message).get(0);
        UUID token = claim(TENANT_A, delivery);
        Instant failedAt = micros(Instant.now());
        // the call was timed on another instance whose clock runs ahead of the one recording the failure
        attempt(TENANT_A, delivery, OutcomeClass.PERMANENT, failedAt.plusSeconds(5), failedAt.plusSeconds(6));

        runInTx(TENANT_A, () -> recorder(TENANT_A).record(delivery, token, 1, OutcomeClass.PERMANENT, failedAt,
                null, false));

        List<StageRow> rows = stages(message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");
        assertThat(rows.get(1).startedAt()).as("clamped to the finish: never a negative duration")
                .isEqualTo(failedAt);
        assertThat(rows.get(1).finishedAt()).isEqualTo(failedAt);
    }

    // ---------------------------------------------------------------- the production wiring under proof

    private JdbcStage1Deliveries stage1(UUID tenant) {
        return new JdbcStage1Deliveries(repository(tenant), writer(tenant));
    }

    private OutcomeRecorder recorder(UUID tenant) {
        return new OutcomeRecorder(new JdbcDeliveryRetryStore(jdbc, context(tenant)), RetryPolicy.defaults(),
                () -> 1.0, writer(tenant));
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

    /** Stage 1's one transaction, as the stage-1 consumer runs it: the fan-out insert, then the guard. */
    private boolean route(UUID tenant, UUID message, Instant acceptedAt, UUID... routes) {
        return inTx(tenant, () -> {
            JdbcStage1Deliveries stage1 = stage1(tenant);
            stage1.insertPerRoute(Arrays.stream(routes).map(route -> delivery(message, acceptedAt, route)).toList());
            return stage1.markMessageRouted(message);
        });
    }

    private static Stage1Delivery delivery(UUID message, Instant acceptedAt, UUID route) {
        return new Stage1Delivery(message, route, acceptedAt, TARGET, null, null, null, null, null, null);
    }

    private UUID claim(UUID tenant, UUID delivery) {
        UUID token = UUID.randomUUID();
        assertThat(inTx(tenant, () -> repository(tenant).claimSend(delivery, token, Instant.now())))
                .as("PENDING/DUE -> SENDING").isTrue();
        return token;
    }

    private void attempt(UUID tenant, UUID delivery, OutcomeClass outcome, Instant startedAt, Instant finishedAt) {
        runInTx(tenant, () -> repository(tenant).recordAttempt(delivery, 1, outcome, startedAt, finishedAt,
                "layer-1 try 1: " + outcome));
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

    /** The stage's rows of the message visible to the CURRENT stamped transaction (its own uncommitted ones too). */
    private long timingRowsInTx(UUID message, String stage) {
        return jdbc.sql("select count(*) from public.message_stage_timing where message_id = :message::uuid"
                        + " and stage = :stage")
                .param("message", message.toString()).param("stage", stage).query(Long.class).single();
    }

    /** Every timing row of the message visible to the CURRENT stamped transaction. */
    private long allTimingRowsInTx(UUID message) {
        return jdbc.sql("select count(*) from public.message_stage_timing where message_id = :message::uuid")
                .param("message", message.toString()).query(Long.class).single();
    }

    // ---------------------------------------------------------------- owner-side seeding and reads (RLS bypassed)

    private record StageRow(String stage, Instant startedAt, Instant finishedAt) {
    }

    private UUID message(UUID tenant, Instant acceptedAt) throws SQLException {
        UUID id = UUID.randomUUID();
        ownerUpdate("insert into public.message (id, tenant_id, interface_id, interface_version_id,"
                        + " source_contract_version_id, business_key, accepted_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "t105-" + id, at(acceptedAt),
                at(acceptedAt));
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

    private String messageState(UUID message) throws SQLException {
        return ownerString("select state from public.message where id = ?", message);
    }

    private String deliveryState(UUID delivery) throws SQLException {
        return ownerString("select state from public.delivery where id = ?", delivery);
    }

    private String ownerString(String sql, Object parameter) throws SQLException {
        try (Connection read = owner.getConnection(); PreparedStatement query = read.prepareStatement(sql)) {
            query.setObject(1, parameter);
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
