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
    private final List<OutboxEntry> outbox = java.util.Collections.synchronizedList(new ArrayList<>());
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
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository.countEventsWithDetail(delivery, DeliveryEventType.REDRIVE,
                        JdbcBulkheadRedrive.SLOT_FREE))).as("F5: the event log names the slot-free path").isEqualTo(1);
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

    // ------------------------------------------------------------------ F4 (review 2026-10-22)

    private JdbcBulkheadRedrive redriveAs(UUID tenant, io.integrationplatform.ingest.port.OutboxWriter writer) {
        return new JdbcBulkheadRedrive(jdbc, DeliveryTestDatabase.tenant(tenant), transactions(), writer);
    }

    private JdbcBulkheadRedrive redriveAs(UUID tenant) {
        return redriveAs(tenant, entry -> {
            outbox.add(entry);
            return outbox.size();
        });
    }

    @Test
    @DisplayName("tenant isolation: tenant B's deferred row of the same target is never picked by tenant A's re-drive"
            + " - only by B's own")
    void anotherTenantsDeferredRowIsNeverPicked() {
        JdbcDeliveryRepository repositoryB =
                new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_B));
        UUID rowOfB = UUID.randomUUID();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_B, () -> repositoryB.create(rowOfB,
                DeliveryTestDatabase.INTERFACE_ID, "tenant-b-1", 1L, target, NOW));
        TransactionTemplate transactions = transactions();
        JdbcBulkheadDeferrals deferralsB = new JdbcBulkheadDeferrals(jdbc,
                DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_B), transactions, new JdbcOrderedStateChange(
                        jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_B), transactions));
        assertThat(deferralsB.defer(rowOfB, NOW, 0, 0.0)).isTrue();

        assertThat(redriveAs(DeliveryTestDatabase.TENANT_A).redriveOneDeferred(target))
                .as("A sees no deferred row of this target").isFalse();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_B, () -> repositoryB.state(rowOfB)))
                .contains(DeliveryState.RETRY_WAIT);
        assertThat(outbox).isEmpty();

        assertThat(redriveAs(DeliveryTestDatabase.TENANT_B).redriveOneDeferred(target)).isTrue();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_B, () -> repositoryB.state(rowOfB)))
                .contains(DeliveryState.DUE);
    }

    @Test
    @DisplayName("the bulkhead_deferrals > 0 predicate: an ordinary failure-retry RETRY_WAIT row (0 deferrals) is not moved")
    void failureRetryRowWithNoDeferralsIsNotMoved() {
        UUID retrying = newDelivery("redrive-failure-retry");
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc.sql("update public.delivery set state = 'RETRY_WAIT',"
                        + " next_attempt_at = :at where id = :id::uuid and bulkhead_deferrals = 0")
                .param("at", java.time.OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC))
                .param("id", retrying.toString()).update());
        assertThat(state(retrying)).contains(DeliveryState.RETRY_WAIT);

        assertThat(redrive().redriveOneDeferred(target)).isFalse();

        assertThat(state(retrying)).contains(DeliveryState.RETRY_WAIT);
        assertThat(outbox).isEmpty();
    }

    @Test
    @DisplayName("FOR UPDATE SKIP LOCKED: two concurrent re-drives, one holding its row mid-transaction, move two"
            + " different rows - each once, one outbox row each")
    void concurrentRedrivesNeverTakeTheSameRow() throws Exception {
        UUID first = newDelivery("redrive-c1");
        UUID second = newDelivery("redrive-c2");
        deferrals().defer(first, NOW, 0, 0.0);
        deferrals().defer(second, NOW.plusSeconds(5), 0, 0.0);
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch letGo = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // thread 1 locks the oldest row and holds it (its outbox append waits) until thread 2 has finished
            java.util.concurrent.Future<Boolean> one = threads.submit(() -> redriveAs(DeliveryTestDatabase.TENANT_A,
                    entry -> {
                        holding.countDown();
                        try {
                            letGo.await(20, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        outbox.add(entry);
                        return outbox.size();
                    }).redriveOneDeferred(target));
            assertThat(holding.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<Boolean> two =
                    threads.submit(() -> redriveAs(DeliveryTestDatabase.TENANT_A).redriveOneDeferred(target));
            assertThat(two.get(20, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the second call skipped the locked row and took the next one, without waiting").isTrue();
            letGo.countDown();
            assertThat(one.get(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            letGo.countDown();
            threads.shutdownNow();
        }

        assertThat(state(first)).contains(DeliveryState.DUE);
        assertThat(state(second)).contains(DeliveryState.DUE);
        assertThat(outbox).extracting(OutboxEntry::aggregateId).containsExactlyInAnyOrder(first, second);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository.countEvents(first, DeliveryEventType.REDRIVE))).isEqualTo(1);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> repository.countEvents(second, DeliveryEventType.REDRIVE))).isEqualTo(1);
    }
}
