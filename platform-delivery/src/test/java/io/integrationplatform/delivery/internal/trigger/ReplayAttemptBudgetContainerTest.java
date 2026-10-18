package io.integrationplatform.delivery.internal.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.delivery.internal.JdbcSendOutcomes;
import io.integrationplatform.delivery.port.RetryCandidate;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T165 Part B (ADR-010 section 6 addendum, contract M5): the layer-2 attempt budget is counted over the CURRENT
 * series - the {@code delivery_attempt} rows started after the delivery's latest {@code REPLAY} event (every row when
 * it was never replayed). An operator retry ({@code REDRIVE}) does not start a series; the ledger keeps every row
 * (R15). Every failure goes through {@link JdbcSendOutcomes#recordFailure} with the stale per-exchange try count the
 * layer-1 route hands over ({@code 1}), so the budget must come from the database, not from the argument.
 */
@Tag("container")
class ReplayAttemptBudgetContainerTest {

    private static final int MAX_ATTEMPTS = 8;
    private static final int STALE_LAYER_ONE_TRY = 1;

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private final UUID tenant = UUID.randomUUID();
    private final TriggerTenantContext context = new TriggerTenantContext();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private Instant clock;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection owner = db.owner(); PreparedStatement insert = owner.prepareStatement(
                "insert into public.tenant (id, code, status, time_zone) values (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            insert.setString(1, tenant.toString());
            insert.setString(2, "t165b-" + tenant);
            insert.executeUpdate();
        }
        pool = db.appPool(2);
        jdbc = JdbcClient.create(pool);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(pool) {
            @Override
            protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                    throws SQLException {
                try (PreparedStatement stamp = connection.prepareStatement(
                        "select set_config('app.tenant_id', ?, true)")) {
                    stamp.setString(1, context.requireTenantId().toString());
                    stamp.execute();
                }
            }
        });
        clock = Instant.now().minusSeconds(600);
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    @Test
    @DisplayName("test 5: after a replay the delivery gets a fresh budget of 8 and dead-letters at the 8th new row")
    void aReplayStartsANewSeries() {
        UUID id = created();
        failTimes(id, MAX_ATTEMPTS);
        assertThat(state(id)).as("series 1 spends the budget").contains(DeliveryState.DEAD_LETTERED);

        replay(id);
        fail(id);
        assertThat(state(id)).as("first failure after the replay is attempt 1 of a new series")
                .contains(DeliveryState.RETRY_WAIT);

        redrive(id);
        failTimes(id, MAX_ATTEMPTS - 1);
        assertThat(state(id)).as("8th failure of the new series").contains(DeliveryState.DEAD_LETTERED);
        assertThat(inTenant(() -> repository().countAttempts(id))).as("series count").isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("test 6: an operator retry (REDRIVE) does not start a new series")
    void aRedriveDoesNotResetTheBudget() {
        UUID id = created();
        failTimes(id, MAX_ATTEMPTS - 1);
        assertThat(state(id)).contains(DeliveryState.RETRY_WAIT);
        assertThat(inTenant(() -> repository().countEvents(id,
                io.integrationplatform.delivery.DeliveryEventType.REDRIVE)))
                .as("6 redrives between 7 failures").isEqualTo(MAX_ATTEMPTS - 2);
        assertThat(lockedAttempts(id)).as("lockDue counts the whole un-replayed series").isEqualTo(MAX_ATTEMPTS - 1);

        redrive(id);
        fail(id);
        assertThat(state(id)).as("8th attempt, no replay: dead-lettered").contains(DeliveryState.DEAD_LETTERED);
        assertThat(inTenant(() -> repository().countAttempts(id))).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("test 7: the ledger keeps both series; retries made stays lifetime; lockDue and backoff use the series")
    void theLedgerKeepsEveryRowAcrossTheReplay() {
        UUID id = created();
        failTimes(id, MAX_ATTEMPTS);
        replay(id);
        Instant beforeFailure = Instant.now();
        fail(id);

        int rows = inTenant(() -> jdbc.sql("select count(*)::int from public.delivery_attempt where delivery_id"
                + " = :id::uuid").params(Map.of("id", id.toString())).query(Integer.class).single());
        assertThat(rows).as("R15: no attempt row deleted by the replay").isEqualTo(MAX_ATTEMPTS + 1);
        assertThat(rows - 1).as("retries made is lifetime: all rows - 1").isEqualTo(MAX_ATTEMPTS);
        assertThat(inTenant(() -> repository().countAttempts(id))).as("budget count is the series").isEqualTo(1);
        assertThat(lockedAttempts(id)).as("lockDue reports the post-replay series").isEqualTo(1);

        Instant next = inTenant(() -> jdbc.sql("select next_attempt_at from public.delivery where id = :id::uuid")
                .params(Map.of("id", id.toString())).query(java.sql.Timestamp.class).single()).toInstant();
        assertThat(next).as("backoff restarts at series attempt 2, not lifetime attempt 10 (1 h cap)")
                .isBefore(beforeFailure.plusSeconds(300));
    }

    // ------------------------------------------------------------------ helpers

    private UUID created() {
        UUID id = UUID.randomUUID();
        Instant at = tick();
        inTenant(() -> {
            repository().create(id, DeliveryTestDatabase.INTERFACE_ID, "t165b-" + id, null,
                    DeliveryTestDatabase.TARGET_ID, at);
            return null;
        });
        return id;
    }

    /** {@code n} failed calls, re-driving between them while the delivery waits for a retry. */
    private void failTimes(UUID id, int n) {
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                redrive(id);
            }
            fail(id);
        }
    }

    /** One claimed call, its layer-1 TRANSIENT attempt row, the failure recorded with the stale layer-1 try count. */
    private void fail(UUID id) {
        UUID token = UUID.randomUUID();
        Instant at = tick();
        inTenant(() -> {
            JdbcDeliveryRepository repository = repository();
            assertThat(repository.claimSend(id, token, at)).as("claim").isTrue();
            repository.recordAttempt(id, 1, OutcomeClass.TRANSIENT, at, at.plusMillis(40), "HTTP 503");
            new JdbcSendOutcomes(jdbc, context).recordFailure(id, token, STALE_LAYER_ONE_TRY,
                    OutcomeClass.TRANSIENT, false);
            return null;
        });
    }

    private void redrive(UUID id) {
        Instant at = tick();
        assertThat(inTenant(() -> repository().redrive(id, at))).as("redrive from RETRY_WAIT").isTrue();
    }

    private void replay(UUID id) {
        Instant at = tick();
        assertThat(inTenant(() -> repository().replay(id, at))).as("replay from DEAD_LETTERED").isTrue();
    }

    private int lockedAttempts(UUID id) {
        return inTenant(() -> new JdbcDeliveryRetryStore(jdbc, context).lockDue(Instant.now().plusSeconds(7200), 50)
                .stream().filter(c -> c.deliveryId().equals(id)).mapToInt(RetryCandidate::attempts)
                .findFirst().orElseThrow());
    }

    private java.util.Optional<DeliveryState> state(UUID id) {
        return inTenant(() -> repository().state(id));
    }

    /** Strictly increasing past instants, so every attempt after a replay starts after its event. */
    private Instant tick() {
        clock = clock.plusSeconds(1);
        return clock;
    }

    private JdbcDeliveryRepository repository() {
        return new JdbcDeliveryRepository(jdbc, context);
    }

    private <T> T inTenant(Supplier<T> work) {
        context.bind(tenant);
        try {
            return transactions.execute(status -> work.get());
        } finally {
            context.unbind();
        }
    }
}
