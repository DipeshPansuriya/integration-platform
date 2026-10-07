package io.integrationplatform.delivery.internal.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.delivery.internal.retry.OutcomeRecorder;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.domain.delivery.OutcomeClassifier;
import io.integrationplatform.ingest.port.OutboxEntry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-100 (E4-F1-S4 @negative @security "401 and 403 are never retried") on lane C's real delivery tables: a target call
 * answered 401 or 403 writes exactly ONE {@code delivery_attempt} row, the one classifier calls it PERMANENT, the
 * recorder dead-letters it through {@link JdbcDeliveryRetryStore}, and the real delivery-retry trigger - which does
 * re-drive a due TRANSIENT control delivery in the same run - never selects it. Each test uses its own tenant so the
 * shared database's other deliveries cannot change the counts.
 */
@Tag("container")
class RefusalNeverRetriedContainerTest {

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private final UUID tenant = UUID.randomUUID();
    private final TriggerTenantContext context = new TriggerTenantContext();
    private final List<OutboxEntry> redriven = new CopyOnWriteArrayList<>();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection owner = db.owner(); PreparedStatement insert = owner.prepareStatement(
                "insert into public.tenant (id, code, status, time_zone) values (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            insert.setString(1, tenant.toString());
            insert.setString(2, "t100-" + tenant);
            insert.executeUpdate();
        }
        pool = db.appPool(2);
        jdbc = JdbcClient.create(pool);
        // The trigger's composition: the bound job tenant is what each transaction stamps (R11, set_config local).
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
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {401, 403})
    @DisplayName("401/403: one attempt row, DEAD_LETTERED PERMANENT, never re-driven by the delivery-retry job")
    void aRefusedCallIsMadeOnceAndNeverRetried(int status) {
        UUID refused = UUID.randomUUID();
        UUID control = UUID.randomUUID();
        Instant past = Instant.now().minusSeconds(120);

        attempt(refused, status, past);
        attempt(control, 503, past);

        assertThat(inTenant(() -> repository().state(refused))).contains(DeliveryState.DEAD_LETTERED);
        assertThat(inTenant(() -> jdbc.sql("select dead_letter_class from public.delivery where id = :id::uuid")
                .params(Map.of("id", refused.toString())).query(String.class).single()))
                .isEqualTo(OutcomeClass.PERMANENT.name());
        assertThat(inTenant(() -> repository().state(control))).contains(DeliveryState.RETRY_WAIT);

        int reDriven = new DeliveryRetryTrigger(() -> List.of(tenant), context, transactions,
                new JdbcDeliveryRetryStore(jdbc, context), entry -> {
                    redriven.add(entry);
                    return redriven.size();
                }, 50).runOnce();

        assertThat(reDriven).as("only the due TRANSIENT control is re-driven").isEqualTo(1);
        assertThat(redriven).extracting(OutboxEntry::aggregateId).containsExactly(control);
        assertThat(inTenant(() -> repository().state(refused))).contains(DeliveryState.DEAD_LETTERED);
        assertThat(inTenant(() -> repository().countAttempts(refused)))
                .as("exactly one delivery_attempt row: the call was made once")
                .isEqualTo(1);
        assertThat(inTenant(() -> jdbc.sql("select layer || ':' || outcome_class from public.delivery_attempt"
                        + " where delivery_id = :id::uuid").params(Map.of("id", refused.toString()))
                .query(String.class).list()))
                .containsExactly("1:PERMANENT");
    }

    /** One finished target call: claim, its attempt row, the classified outcome recorded - one transaction. */
    private void attempt(UUID deliveryId, int status, Instant at) {
        UUID token = UUID.randomUUID();
        OutcomeClass outcome = OutcomeClassifier.ofHttpStatus(status, false);
        inTenant(() -> {
            JdbcDeliveryRepository repository = repository();
            repository.create(deliveryId, DeliveryTestDatabase.INTERFACE_ID, "t100-" + deliveryId, null,
                    DeliveryTestDatabase.TARGET_ID, at);
            repository.claimSend(deliveryId, token, at);
            repository.recordAttempt(deliveryId, 1, outcome, at, at.plusMillis(40), "HTTP " + status);
            new OutcomeRecorder(new JdbcDeliveryRetryStore(jdbc, context), RetryPolicy.defaults(), () -> 0.0)
                    .record(deliveryId, token, repository.countAttempts(deliveryId), outcome, at, null, false);
            return null;
        });
    }

    private JdbcDeliveryRepository repository() {
        return new JdbcDeliveryRepository(jdbc, context);
    }

    private <T> T inTenant(java.util.function.Supplier<T> work) {
        context.bind(tenant);
        try {
            return transactions.execute(status -> work.get());
        } finally {
            context.unbind();
        }
    }
}
