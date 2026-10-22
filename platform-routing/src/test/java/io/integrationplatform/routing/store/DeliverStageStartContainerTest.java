package io.integrationplatform.routing.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.stage1.JdbcStage1Deliveries;
import io.integrationplatform.delivery.port.Stage1Delivery;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.internal.timing.JdbcStageTimingWriter;
import io.integrationplatform.ingest.port.StageTimings.Stage;
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
 * Lane timeline-stage-order (BS-09): the agreed order INTAKE, ROUTE, TRANSFORM, DELIVER. The attempt row starts
 * BEFORE the transform runs (LayerOneRouteBuilder takes the clock, then the API call builds the body), so a DELIVER
 * row started at the first attempt's start began before TRANSFORM did. The DELIVER row now starts when the transform
 * finished (the send itself), so the timeline reads in pipeline order by start time - also for a delivery that
 * retried, whose DELIVER row still runs to the final move.
 *
 * <pre>
 * Scenario: A delivery that retried starts DELIVER no earlier than TRANSFORM, finished at the final move
 * Scenario: A transform that only succeeded on the second attempt still starts before DELIVER
 * Scenario: A message with no TRANSFORM row keeps DELIVER at the first attempt
 * </pre>
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class DeliverStageStartContainerTest {

    private static final UUID TENANT = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000d0001");
    private static final UUID TARGET = UUID.fromString("dddddddd-0000-0000-0000-00000000d001");

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
                + " (?, 'deliver-start', 'ACTIVE', 'Etc/UTC')", TENANT);
    }

    @Test
    @DisplayName("a delivery that retried starts DELIVER no earlier than TRANSFORM, finished at the final move")
    void aRetriedDeliveryStartsDeliverAfterTransform() throws SQLException {
        Instant base = micros(Instant.now()).minusSeconds(60);
        Seeded seeded = routed(base);
        UUID first = claim(seeded.delivery);
        // attempt 1 is clocked first; the transform runs inside it
        record(seeded.message, Stage.TRANSFORM, base.plusMillis(5), base.plusMillis(15));
        attempt(seeded.delivery, OutcomeClass.TRANSIENT, base, base.plusMillis(20));
        inTx(() -> repository().scheduleRetry(seeded.delivery, first, base.plusSeconds(30), base.plusMillis(21)));
        ownerUpdate("update public.delivery set state = 'DUE' where id = ?", seeded.delivery);
        UUID second = claim(seeded.delivery);
        attempt(seeded.delivery, OutcomeClass.SUCCESS, base.plusSeconds(30), base.plusMillis(30_100));
        Instant moved = base.plusMillis(30_100);
        inTx(() -> {
            assertThat(repository().markDelivered(seeded.delivery, second, moved)).isTrue();
            writer().recordDeliverFinished(seeded.delivery, moved);
            return null;
        });

        List<StageRow> rows = stages(seeded.message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("ROUTE", "TRANSFORM", "DELIVER");
        StageRow transform = rows.get(1);
        StageRow deliver = rows.get(2);
        assertThat(deliver.startedAt()).as("DELIVER does not start before TRANSFORM")
                .isAfterOrEqualTo(transform.startedAt());
        assertThat(deliver.startedAt()).as("DELIVER starts when the transform finished, the send itself")
                .isEqualTo(transform.finishedAt());
        assertThat(deliver.finishedAt()).as("and runs to the final move, the retry included").isEqualTo(moved);
    }

    @Test
    @DisplayName("a transform that only succeeded on the second attempt still starts before DELIVER")
    void aTransformThatSucceededOnTheSecondAttemptStartsBeforeDeliver() throws SQLException {
        Instant base = micros(Instant.now()).minusSeconds(60);
        Seeded seeded = routed(base);
        UUID first = claim(seeded.delivery);
        attempt(seeded.delivery, OutcomeClass.TRANSIENT, base, base.plusMillis(20)); // no mapping result yet
        inTx(() -> repository().scheduleRetry(seeded.delivery, first, base.plusSeconds(30), base.plusMillis(21)));
        ownerUpdate("update public.delivery set state = 'DUE' where id = ?", seeded.delivery);
        UUID second = claim(seeded.delivery);
        record(seeded.message, Stage.TRANSFORM, base.plusSeconds(30).plusMillis(5), base.plusSeconds(30).plusMillis(9));
        attempt(seeded.delivery, OutcomeClass.SUCCESS, base.plusSeconds(30), base.plusMillis(30_100));
        Instant moved = base.plusMillis(30_100);
        inTx(() -> {
            repository().markDelivered(seeded.delivery, second, moved);
            writer().recordDeliverFinished(seeded.delivery, moved);
            return null;
        });

        List<StageRow> rows = stages(seeded.message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("ROUTE", "TRANSFORM", "DELIVER");
        assertThat(rows.get(2).startedAt()).isAfterOrEqualTo(rows.get(1).startedAt());
        assertThat(rows.get(2).startedAt()).isEqualTo(rows.get(1).finishedAt());
    }

    @Test
    @DisplayName("a message with no TRANSFORM row keeps DELIVER at the first attempt")
    void aMessageWithNoTransformRowKeepsDeliverAtTheFirstAttempt() throws SQLException {
        Instant base = micros(Instant.now()).minusSeconds(60);
        Seeded seeded = routed(base);
        UUID token = claim(seeded.delivery);
        attempt(seeded.delivery, OutcomeClass.SUCCESS, base.plusMillis(7), base.plusMillis(40));
        inTx(() -> {
            repository().markDelivered(seeded.delivery, token, base.plusMillis(41));
            writer().recordDeliverFinished(seeded.delivery, base.plusMillis(41));
            return null;
        });

        List<StageRow> rows = stages(seeded.message);
        assertThat(rows).extracting(StageRow::stage).containsExactly("ROUTE", "DELIVER");
        assertThat(rows.get(1).startedAt()).isEqualTo(base.plusMillis(7));
    }

    // ---------------------------------------------------------------- seeding (the DeadLetterDeliverStageContainerTest shape)

    private record Seeded(UUID message, UUID delivery) {
    }

    private Seeded routed(Instant acceptedAt) throws SQLException {
        UUID message = message(acceptedAt);
        inTx(() -> {
            JdbcStage1Deliveries stage1 = new JdbcStage1Deliveries(repository(), writer());
            stage1.insertPerRoute(List.of(
                    new Stage1Delivery(message, UUID.randomUUID(), acceptedAt, TARGET, null, null, null, null, null)));
            return stage1.markMessageRouted(message);
        });
        return new Seeded(message, deliveries(message).get(0));
    }

    private UUID claim(UUID delivery) {
        UUID token = UUID.randomUUID();
        assertThat(inTx(() -> repository().claimSend(delivery, token, Instant.now()))).isTrue();
        return token;
    }

    private void attempt(UUID delivery, OutcomeClass outcome, Instant started, Instant finished) {
        inTx(() -> {
            repository().recordAttempt(delivery, 1, outcome, started, finished, null);
            return null;
        });
    }

    private void record(UUID message, Stage stage, Instant started, Instant finished) {
        inTx(() -> {
            writer().record(message, stage, started, finished);
            return null;
        });
    }

    private JdbcStageTimingWriter writer() {
        return new JdbcStageTimingWriter(jdbc, context());
    }

    private JdbcDeliveryRepository repository() {
        return new JdbcDeliveryRepository(jdbc, context());
    }

    private static TenantContext context() {
        return () -> Optional.of(TENANT);
    }

    private <T> T inTx(Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.sql("select set_config('app.tenant_id', :tenant, true)").param("tenant", TENANT.toString())
                    .query().singleValue();
            return work.get();
        });
    }

    private record StageRow(String stage, Instant startedAt, Instant finishedAt) {
    }

    private UUID message(Instant acceptedAt) throws SQLException {
        UUID id = UUID.randomUUID();
        ownerUpdate("insert into public.message (id, tenant_id, interface_id, interface_version_id,"
                        + " source_contract_version_id, business_key, accepted_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, TENANT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "deliver-start-" + id,
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
