package io.integrationplatform.delivery.internal.deferral;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.ordering.JdbcOrderedStateChange;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drain fix (ADR-010 section 2 addendum) on the real PostgreSQL: bulkhead full -> a delivery defers to
 * {@code RETRY_WAIT} with a backoff time still in the future -> the slot-free re-drive moves it
 * {@code RETRY_WAIT -> DUE} at once (no job, no wait for {@code next_attempt_at}) and appends one
 * {@code DELIVERY_DUE} outbox row. One call moves exactly one row (the oldest), only of the asked target, only a
 * bulkhead-deferred one, and nothing when nothing waits.
 */
@Tag("container")
class JdbcBulkheadRedriveContainerTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.DAYS).plusSeconds(43_200);

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private final List<OutboxEntry> outbox = new ArrayList<>();
    private final UUID target = UUID.randomUUID(); // own target: other tests leave deferred rows on the shared one
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private DeliveryTestDatabase.DeliveryTx tx;
    private JdbcDeliveryRepository repository;

    @BeforeEach
    void setUp() {
        pool = db.appPool(4);
        jdbc = JdbcClient.create(pool);
        tx = DeliveryTestDatabase.tx(pool);
        repository = new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A));
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(new DataSourceTransactionManager((DataSource) pool));
    }

    private JdbcBulkheadDeferrals deferrals() {
        TransactionTemplate transactions = transactions();
        return new JdbcBulkheadDeferrals(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A),
                transactions, new JdbcOrderedStateChange(jdbc,
                        DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A), transactions));
    }

    private JdbcBulkheadRedrive redrive() {
        return new JdbcBulkheadRedrive(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A),
                transactions(), entry -> {
                    outbox.add(entry);
                    return outbox.size();
                });
    }

    private UUID newDelivery(String businessKey) {
        UUID id = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository.create(id,
                DeliveryTestDatabase.INTERFACE_ID, businessKey, 1L, target, NOW));
        return id;
    }

    private Optional<DeliveryState> state(UUID id) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository.state(id));
    }

    @Test
    @DisplayName("a bulkhead-deferred delivery is moved RETRY_WAIT -> DUE at once, with one DELIVERY_DUE outbox row"
            + " and a REDRIVE event, though its backoff time is still ahead")
    void deferredDeliveryIsRedrivenImmediately() {
        UUID delivery = newDelivery("redrive-1");
        assertThat(deferrals().defer(delivery, Instant.now(), 0, 1.0)).isTrue(); // full jitter: waits ~1 s ahead
        assertThat(state(delivery)).contains(DeliveryState.RETRY_WAIT);

        assertThat(redrive().redriveOneDeferred(target)).isTrue();

        assertThat(state(delivery)).contains(DeliveryState.DUE);
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).topic()).isEqualTo(OutboxTopic.DELIVERY_DUE);
        assertThat(outbox.get(0).aggregateId()).isEqualTo(delivery);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository.countEvents(delivery, DeliveryEventType.REDRIVE))).isEqualTo(1);
    }

    @Test
    @DisplayName("one call moves exactly one delivery, the oldest deferral; the next call takes the next one")
    void oneFreedSlotMovesOneDelivery() {
        UUID first = newDelivery("redrive-a");
        UUID second = newDelivery("redrive-b");
        deferrals().defer(first, NOW, 0, 0.0);
        deferrals().defer(second, NOW.plusSeconds(5), 0, 0.0);

        assertThat(redrive().redriveOneDeferred(target)).isTrue();
        assertThat(state(first)).contains(DeliveryState.DUE);
        assertThat(state(second)).contains(DeliveryState.RETRY_WAIT);

        assertThat(redrive().redriveOneDeferred(target)).isTrue();
        assertThat(state(second)).contains(DeliveryState.DUE);
        assertThat(outbox).hasSize(2);
    }

    @Test
    @DisplayName("nothing waiting, another target, or a failure-retry (never bulkhead-deferred) row: no effect")
    void nothingToRedriveIsNoEffect() {
        UUID pending = newDelivery("redrive-pending");
        assertThat(redrive().redriveOneDeferred(target)).as("only PENDING exists").isFalse();

        UUID deferred = newDelivery("redrive-other");
        deferrals().defer(deferred, NOW, 0, 0.0);
        assertThat(redrive().redriveOneDeferred(UUID.randomUUID())).as("a different target").isFalse();

        assertThat(state(pending)).contains(DeliveryState.PENDING);
        assertThat(state(deferred)).contains(DeliveryState.RETRY_WAIT);
        assertThat(outbox).isEmpty();
    }
}
