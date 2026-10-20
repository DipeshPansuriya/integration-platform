package io.integrationplatform.delivery.internal.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.delivery.internal.deferral.JdbcDeliveryBulkheadFacts;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.delivery.port.InstanceReadiness;
import io.integrationplatform.delivery.port.TargetBreakerFacts;
import io.integrationplatform.delivery.port.TargetBreakerFacts.BreakerFacts;
import io.integrationplatform.delivery.port.TargetRedriveCap;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.ingest.port.OutboxEntry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * T-183 (ADR-010 section 6 last row, section 8; ADR-011 Decision 1) on the REAL delivery tables: the wired
 * per-delivery target facts ({@link JdbcDeliveryBulkheadFacts}, read-only SQL under row-level security) and the
 * real store move - from due {@code RETRY_WAIT} rows seeded through the repository like every consumer path
 * (create, claim, scheduleRetry) - at most the target's {@code halfOpenTrialCalls} re-drive per pass. The
 * breaker facts answer a fixture value here; the Resilience4j registry behind {@code TargetBreakerFacts} is
 * routing's own state (the lane crossing stays with the wiring task). Each test uses its own tenant.
 */
@Tag("container")
class DeliveryHalfOpenRedriveContainerTest {

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private final UUID tenant = UUID.randomUUID();
    private final TriggerTenantContext context = new TriggerTenantContext();
    private final List<OutboxEntry> handed = new ArrayList<>();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection owner = db.owner(); PreparedStatement insert = owner.prepareStatement(
                "insert into public.tenant (id, code, status, time_zone) values (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            insert.setString(1, tenant.toString());
            insert.setString(2, "t183-" + tenant);
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
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    @Test
    @DisplayName("50 rows due, breaker half-open on trial 3: exactly 3 move to DUE, 47 keep waiting")
    void aHalfOpenBreakerReDrivesOnlyItsTrialCountOverTheRealTables() {
        Instant past = Instant.now().minusSeconds(60);
        List<UUID> waiting = waitingOn(DeliveryTestDatabase.TARGET_ID, past, 50);
        TargetBreakerFacts breaker = target -> target.equals(DeliveryTestDatabase.TARGET_ID)
                ? Optional.of(new BreakerFacts(BreakerFacts.State.HALF_OPEN, 3))
                : Optional.empty();

        int moved = trigger(breaker).runOnce();

        assertThat(moved).isEqualTo(3);
        assertThat(waiting).filteredOn(id -> state(id) == DeliveryState.DUE).hasSize(3);
        assertThat(waiting).filteredOn(id -> state(id) == DeliveryState.RETRY_WAIT).hasSize(47);
    }

    @Test
    @DisplayName("No breaker facts for another target: its due rows all re-drive - the cap never stalls it")
    void aTargetWithoutBreakerFactsIsNeverCapped() {
        Instant past = Instant.now().minusSeconds(60);
        UUID secondTarget = UUID.randomUUID();
        List<UUID> capped = waitingOn(DeliveryTestDatabase.TARGET_ID, past, 5);
        List<UUID> free = waitingOn(secondTarget, past, 5);
        TargetBreakerFacts breaker = target -> target.equals(DeliveryTestDatabase.TARGET_ID)
                ? Optional.of(new BreakerFacts(BreakerFacts.State.HALF_OPEN, 3))
                : Optional.empty();

        int moved = trigger(breaker).runOnce();

        assertThat(moved).isEqualTo(8);
        assertThat(free).allMatch(id -> state(id) == DeliveryState.DUE);
        assertThat(capped).filteredOn(id -> state(id) == DeliveryState.DUE).hasSize(3);
    }

    private DeliveryRetryTrigger trigger(TargetBreakerFacts breaker) {
        handed.clear();
        return new DeliveryRetryTrigger(() -> List.of(tenant), context, transactions,
                new JdbcDeliveryRetryStore(jdbc, context), entry -> {
                    handed.add(entry);
                    return handed.size();
                }, 100, InstanceReadiness.alwaysReady(),
                (DeliveryBulkheadFacts) new JdbcDeliveryBulkheadFacts(jdbc, context, transactions),
                TargetRedriveCap.unlimited(), breaker);
    }

    /** Seeds a due RETRY_WAIT delivery the consumer path would leave behind: create -> claim -> scheduleRetry. */
    private List<UUID> waitingOn(UUID target, Instant at, int count) {
        JdbcDeliveryRepository repository = repository();
        JdbcDeliveryRetryStore store = new JdbcDeliveryRetryStore(jdbc, context);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant dueAt = at.plusMillis(i);
            UUID id = UUID.randomUUID();
            UUID token = UUID.randomUUID();
            inTenant(() -> {
                repository.create(id, DeliveryTestDatabase.INTERFACE_ID, "t183-" + id, null, target,
                        dueAt);
                repository.claimSend(id, token, at);
                return null;
            });
            inTenant(() -> store.scheduleRetry(id, token, at));
            ids.add(id);
        }
        return ids;
    }

    private DeliveryState state(UUID id) {
        return inTenant(() -> repository().state(id)).orElseThrow();
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