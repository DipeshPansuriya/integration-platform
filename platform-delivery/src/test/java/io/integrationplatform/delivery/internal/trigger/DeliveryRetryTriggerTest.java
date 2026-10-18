package io.integrationplatform.delivery.internal.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.delivery.internal.retry.FakeDeliveryRetryStore;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.NoTenantBoundException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * T-090 (E2-F2-S3): the minimal delivery-retry trigger - polling, not a scheduler. Per active tenant it runs
 * ONE transaction that locks due {@code RETRY_WAIT} deliveries through lane C's store contract
 * ({@code FOR UPDATE SKIP LOCKED}, proven against the real tables by the container sibling), moves them to
 * {@code DUE} and re-drives each by appending a {@code DELIVERY_DUE} outbox row - in the same transaction, so a
 * crash between the move and the append leaves the delivery due for the next run and the job never re-drives
 * twice from its own half-written state. It never opens a transaction outside the store's, and no broker client
 * is reachable from here (the relay publishes the row it appended).
 */
class DeliveryRetryTriggerTest {

    private static final int BATCH = 50;
    private static final Instant BASE = Instant.parse("2026-10-05T12:00:00Z").minus(Duration.ofDays(1));

    private static final UUID TENANT_A = id("tenant-a");
    private static final UUID TENANT_B = id("tenant-b");
    private static final UUID D1 = id("d1");
    private static final UUID D2 = id("d2");
    private static final UUID D3 = id("d3");

    private final TriggerTenantContext context = new TriggerTenantContext();
    private final FakeDeliveryRetryStore store = new FakeDeliveryRetryStore(context);
    private final FakeOutboxWriter outbox = new FakeOutboxWriter(context);
    private final FakeTransactions transactions = new FakeTransactions(store, outbox);

    // ---------- the happy cycle ----------

    @Test
    void everyDueRetryWaitDeliveryMovesToDueAndIsRedrivenExactlyOnce() {
        store.seed(TENANT_A, D1, DeliveryState.RETRY_WAIT, 2, BASE, null);
        store.seed(TENANT_A, D2, DeliveryState.RETRY_WAIT, 3, BASE.plusSeconds(1), null);
        store.seed(TENANT_A, D3, DeliveryState.RETRY_WAIT, 1, Instant.now().plus(Duration.ofMinutes(10)), null);

        int reDriven = trigger(() -> List.of(TENANT_A)).runOnce();

        assertThat(reDriven).as("the not-yet-due delivery waits for a later run").isEqualTo(2);
        assertThat(store.stateOf(D1)).isEqualTo(DeliveryState.DUE);
        assertThat(store.stateOf(D2)).isEqualTo(DeliveryState.DUE);
        assertThat(store.stateOf(D3)).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(outbox.committedEntries())
                .extracting(OutboxEntry::topic, OutboxEntry::aggregateId, OutboxEntry::recordKey)
                .containsExactly(
                        new Tuple(OutboxTopic.DELIVERY_DUE, D1, "delivery:" + D1),
                        new Tuple(OutboxTopic.DELIVERY_DUE, D2, "delivery:" + D2));
    }

    @Test
    void eachTenantIsProcessedInsideItsOwnTransactionAndTheThreadIsLeftUnbound() {
        store.seed(TENANT_A, D1, DeliveryState.RETRY_WAIT, 1, BASE, null);
        store.seed(TENANT_B, D2, DeliveryState.RETRY_WAIT, 1, BASE, null);
        store.seed(TENANT_B, D3, DeliveryState.PENDING, 0, BASE, null);

        int reDriven = trigger(() -> List.of(TENANT_A, TENANT_B)).runOnce();

        assertThat(reDriven).isEqualTo(2);
        assertThat(store.tenantsSeen()).containsExactly(TENANT_A, TENANT_B);
        assertThat(store.stateOf(D3)).as("a PENDING delivery is not this job's to move")
                .isEqualTo(DeliveryState.PENDING);
        assertThat(context.currentTenantId()).as("unbound after the run").isEmpty();
    }

    @Test
    void aDeliveryTheStoreWillNotMoveIsNotAppended() {
        store.seed(TENANT_A, D1, DeliveryState.RETRY_WAIT, 1, BASE, null);
        store.seed(TENANT_A, D2, DeliveryState.RETRY_WAIT, 1, BASE.plusSeconds(1), null);
        store.refuseMarkDue(D2); // an operator moved it after the lock: 0 rows -> false, no exception

        int reDriven = trigger(() -> List.of(TENANT_A)).runOnce();

        assertThat(reDriven).as("only the delivery actually moved counts").isEqualTo(1);
        assertThat(outbox.committedEntries()).extracting(OutboxEntry::aggregateId).containsExactly(D1);
    }

    // ---------- crash and concurrency ----------

    @Test
    void aCrashMidTransactionLeavesEveryDeliveryForTheNextRunAndAppendsNothing() {
        store.seed(TENANT_A, D1, DeliveryState.RETRY_WAIT, 1, BASE, null);
        store.seed(TENANT_A, D2, DeliveryState.RETRY_WAIT, 2, BASE.plusSeconds(1), null);
        outbox.failNextAppend(); // the process dies while the transaction writes

        DeliveryRetryTrigger trigger = trigger(() -> List.of(TENANT_A));
        assertThatThrownBy(trigger::runOnce).isInstanceOf(IllegalStateException.class);

        assertThat(store.stateOf(D1)).as("the rolled-back moves are gone").isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(store.stateOf(D2)).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(outbox.committedEntries()).as("nothing half-appended survived").isEmpty();

        assertThat(trigger.runOnce()).as("a clean restart re-drives both - at-least-once by this job").isEqualTo(2);
        assertThat(outbox.committedEntries()).hasSize(2);
    }

    // B24 guard: a race in the shared fake shows only under repetition; one pass hid it (flaked in the reactor).
    @RepeatedTest(200)
    void twoTriggerInstancesNeverRedriveTheSameDeliveryTwice() throws Exception {
        store.seed(TENANT_A, D1, DeliveryState.RETRY_WAIT, 1, BASE, null);
        store.seed(TENANT_A, D2, DeliveryState.RETRY_WAIT, 1, BASE.plusSeconds(1), null);
        DeliveryRetryTrigger first = trigger(() -> List.of(TENANT_A));
        DeliveryRetryTrigger second = trigger(() -> List.of(TENANT_A));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread one = new Thread(() -> runQuietly(first, ready, go, failure));
        Thread two = new Thread(() -> runQuietly(second, ready, go, failure));
        one.start();
        two.start();
        ready.await();
        go.countDown();
        one.join();
        two.join();

        assertThat(failure.get()).isNull();
        assertThat(outbox.committedEntries())
                .extracting(OutboxEntry::aggregateId)
                .as("SKIP LOCKED: each due delivery re-driven by exactly one instance")
                .containsExactlyInAnyOrder(D1, D2);
        assertThat(store.stateOf(D1)).isEqualTo(DeliveryState.DUE);
        assertThat(store.stateOf(D2)).isEqualTo(DeliveryState.DUE);
    }

    // ---------- cycle shape ----------

    @Test
    void anotherTenantsRowsAreInvisibleAndAnUnboundClaimFailsClosed() {
        store.seed(TENANT_B, D1, DeliveryState.RETRY_WAIT, 1, BASE, null);

        assertThat(trigger(() -> List.of(TENANT_A)).runOnce()).as("nothing is due for tenant A").isZero();
        assertThatThrownBy(() -> store.lockDue(Instant.now(), BATCH))
                .as("the store only answers inside a begun tenant-bound transaction")
                .isInstanceOfAny(IllegalStateException.class, NoTenantBoundException.class);
    }

    @Test
    void anEmptyTenantListDoesNothing() {
        store.seed(TENANT_A, D1, DeliveryState.RETRY_WAIT, 1, BASE, null);

        assertThat(trigger(List::of).runOnce()).isZero();
        assertThat(store.stateOf(D1)).isEqualTo(DeliveryState.RETRY_WAIT);
    }

    @Test
    void candidatesComeBackOldestRetryTimeFirstAndTheBatchLimitSplitsTheRest() {
        store.seed(TENANT_A, D1, DeliveryState.RETRY_WAIT, 1, BASE, null);
        store.seed(TENANT_A, D2, DeliveryState.RETRY_WAIT, 1, BASE.plusSeconds(1), null);
        DeliveryRetryTrigger oneAtATime = new DeliveryRetryTrigger(() -> List.of(TENANT_A), context, transactions,
                store, outbox, 1);

        assertThat(oneAtATime.runOnce()).isEqualTo(1);
        assertThat(outbox.committedEntries()).extracting(OutboxEntry::aggregateId)
                .as("the delivery that became due first is re-driven first").containsExactly(D1);
        assertThat(oneAtATime.runOnce()).isEqualTo(1);
        assertThat(outbox.committedEntries()).hasSize(2);
    }

    // ---------- helpers ----------

    private DeliveryRetryTrigger trigger(ActiveTenants tenants) {
        return new DeliveryRetryTrigger(tenants, context, transactions, store, outbox, BATCH);
    }

    private static void runQuietly(DeliveryRetryTrigger trigger, CountDownLatch ready, CountDownLatch go,
            AtomicReference<Throwable> failure) {
        ready.countDown();
        try {
            go.await();
            trigger.runOnce();
        } catch (Throwable thrown) {
            failure.compareAndSet(null, thrown);
        }
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** Opens one fake transaction over the store and the writer, then commits or rolls both back. */
    private static final class FakeTransactions implements TransactionOperations {

        private final FakeDeliveryRetryStore store;
        private final FakeOutboxWriter outbox;

        FakeTransactions(FakeDeliveryRetryStore store, FakeOutboxWriter outbox) {
            this.store = store;
            this.outbox = outbox;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            store.beginTransaction();
            outbox.beginTransaction();
            try {
                T result = action.doInTransaction(null);
                outbox.commitTransaction();
                store.commitTransaction();
                return result;
            } catch (RuntimeException failure) {
                outbox.rollbackTransaction();
                store.rollbackTransaction();
                throw failure;
            }
        }
    }

    /** An outbox honouring the writer contract: bound tenant, caller's transaction, rows visible only after commit. */
    private static final class FakeOutboxWriter implements OutboxWriter {

        private final TriggerTenantContext tenantContext;
        private final List<OutboxEntry> committed = new CopyOnWriteArrayList<>();
        private final ThreadLocal<List<OutboxEntry>> staged = ThreadLocal.withInitial(java.util.ArrayList::new);
        private final ThreadLocal<Boolean> inTransaction = ThreadLocal.withInitial(() -> false);
        private final AtomicLong ids = new AtomicLong();
        private volatile boolean failNextAppend;

        FakeOutboxWriter(TriggerTenantContext tenantContext) {
            this.tenantContext = tenantContext;
        }

        void failNextAppend() {
            failNextAppend = true;
        }

        List<OutboxEntry> committedEntries() {
            return List.copyOf(committed);
        }

        void beginTransaction() {
            inTransaction.set(true);
        }

        void commitTransaction() {
            committed.addAll(staged.get());
            staged.get().clear();
            inTransaction.set(false);
        }

        void rollbackTransaction() {
            staged.get().clear();
            inTransaction.set(false);
        }

        @Override
        public long append(OutboxEntry entry) {
            if (!inTransaction.get()) {
                throw new IllegalStateException("the writer was called outside the caller's transaction");
            }
            tenantContext.requireTenantId(); // fails closed exactly as the real writer does (R08, R11)
            if (failNextAppend) {
                failNextAppend = false;
                throw new IllegalStateException("crash while appending the re-drive row");
            }
            staged.get().add(entry);
            return ids.incrementAndGet();
        }
    }
}
