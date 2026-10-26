package io.integrationplatform.delivery.internal.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.delivery.internal.retry.FakeDeliveryRetryStore;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.delivery.port.DeliveryReconcileStore;
import io.integrationplatform.delivery.port.TargetSendFacts;
import io.integrationplatform.delivery.port.UnroutedMessageSweep;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts.BulkheadFacts;
import io.integrationplatform.delivery.port.InstanceReadiness;
import io.integrationplatform.delivery.port.TargetRedriveCap;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.NoTenantBoundException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

    // ---------- T-201 (E10-F15): readiness DOWN skips the whole retry pass ----------

    @Test
    void aNotReadyInstanceSkipsTheWholeRetryPassAndResumesWhenReady() {
        store.seed(TENANT_A, D1, DeliveryState.RETRY_WAIT, 1, BASE, null);
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean(false);
        DeliveryRetryTrigger gated = new DeliveryRetryTrigger(() -> List.of(TENANT_A), context, transactions,
                store, outbox, BATCH, (tenant, delivery) -> ready.get());

        assertThat(gated.runOnce()).isZero();
        assertThat(store.tenantsSeen()).as("no tenant pass is opened on a not-ready instance").isEmpty();
        assertThat(store.stateOf(D1)).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(outbox.committedEntries()).isEmpty();

        ready.set(true);
        assertThat(gated.runOnce()).as("ready again: the pass runs").isEqualTo(1);
        assertThat(store.stateOf(D1)).isEqualTo(DeliveryState.DUE);
    }

    // ---------- T-199: per-target re-drive cap (ADR-010 section 2) ----------

    private static final UUID TARGET_A = id("target-a");
    private static final UUID TARGET_B = id("target-b");

    @Test
    void aRunReDrivesAtMostMaxConcurrentSendsTimesInstancesBulkheadDeferredDeliveriesPerTarget() {
        Map<UUID, BulkheadFacts> facts = new HashMap<>();
        List<UUID> deferred = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            UUID delivery = id("deferred-" + i);
            deferred.add(delivery);
            store.seed(TENANT_A, delivery, DeliveryState.RETRY_WAIT, 1, BASE.plusMillis(i), null);
            facts.put(delivery, new BulkheadFacts(TARGET_A, null, 3));
        }

        int reDriven = cappedTrigger(facts, 10, 2, 1000).runOnce();

        assertThat(reDriven).as("max_concurrent_sends 10 x 2 instances").isEqualTo(20);
        assertThat(deferred).filteredOn(d -> store.stateOf(d) == DeliveryState.DUE).hasSize(20);
        assertThat(deferred).filteredOn(d -> store.stateOf(d) == DeliveryState.RETRY_WAIT)
                .as("the rest wait for the next run").hasSize(480);
        assertThat(deferred.subList(0, 20)).as("oldest retry time first")
                .allMatch(d -> store.stateOf(d) == DeliveryState.DUE);
        assertThat(outbox.committedEntries()).hasSize(20);
    }

    @Test
    void theCapIsPerTargetAndNeverCountsDeliveriesThatWereNotBulkheadDeferred() {
        Map<UUID, BulkheadFacts> facts = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            UUID a = id("a-deferred-" + i);
            store.seed(TENANT_A, a, DeliveryState.RETRY_WAIT, 1, BASE.plusMillis(i), null);
            facts.put(a, new BulkheadFacts(TARGET_A, null, 1));
            UUID b = id("b-deferred-" + i);
            store.seed(TENANT_A, b, DeliveryState.RETRY_WAIT, 1, BASE.plusMillis(i), null);
            facts.put(b, new BulkheadFacts(TARGET_B, null, 1));
            UUID plain = id("a-plain-retry-" + i);
            store.seed(TENANT_A, plain, DeliveryState.RETRY_WAIT, 2, BASE.plusMillis(i), null);
            facts.put(plain, new BulkheadFacts(TARGET_A, null, 0));
        }

        int reDriven = cappedTrigger(facts, 1, 2, BATCH).runOnce();

        assertThat(reDriven).as("2 deferred per target + 5 ordinary retries").isEqualTo(9);
        assertThat(store.stateOf(id("a-deferred-1"))).isEqualTo(DeliveryState.DUE);
        assertThat(store.stateOf(id("a-deferred-2"))).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(store.stateOf(id("b-deferred-1"))).isEqualTo(DeliveryState.DUE);
        assertThat(store.stateOf(id("b-deferred-2"))).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(store.stateOf(id("a-plain-retry-4"))).isEqualTo(DeliveryState.DUE);
    }

    @Test
    void theNextRunTakesTheNextCapOfTheBacklog() {
        Map<UUID, BulkheadFacts> facts = new HashMap<>();
        for (int i = 0; i < 7; i++) {
            UUID delivery = id("backlog-" + i);
            store.seed(TENANT_A, delivery, DeliveryState.RETRY_WAIT, 1, BASE.plusMillis(i), null);
            facts.put(delivery, new BulkheadFacts(TARGET_A, null, 2));
        }
        DeliveryRetryTrigger trigger = cappedTrigger(facts, 3, 1, BATCH);

        assertThat(List.of(trigger.runOnce(), trigger.runOnce(), trigger.runOnce())).containsExactly(3, 3, 1);
    }

    // ---------- T-183: the half-open re-drive cap (ADR-010 section 6, S-i) ----------

    @Test
    void aRunReDrivesAtMostTheTrialCallCountOfCircuitDeferredDeliveriesPerTarget() {
        Map<UUID, UUID> circuitDeferred = new HashMap<>();
        List<UUID> waiting = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID delivery = id("circuit-" + i);
            waiting.add(delivery);
            store.seed(TENANT_A, delivery, DeliveryState.RETRY_WAIT, 1, BASE.plusMillis(i), null);
            circuitDeferred.put(delivery, TARGET_A);
        }

        int reDriven = halfOpenTrigger(circuitDeferred, 3).runOnce();

        assertThat(reDriven).as("50 waiting on one open breaker, trial-call count 3").isEqualTo(3);
        assertThat(waiting).filteredOn(d -> store.stateOf(d) == DeliveryState.DUE).hasSize(3);
        assertThat(waiting.subList(0, 3)).as("oldest retry time first")
                .allMatch(d -> store.stateOf(d) == DeliveryState.DUE);
        assertThat(waiting).filteredOn(d -> store.stateOf(d) == DeliveryState.RETRY_WAIT).hasSize(47);
        assertThat(outbox.committedEntries()).as("the rows-whatever-moved set is the 3 moved").hasSize(3);
    }

    @Test
    void ifTheBreakerReOpensTheNextPassReDrivesAtMostTheTrialCountAgain() {
        Map<UUID, UUID> circuitDeferred = new HashMap<>();
        for (int i = 0; i < 7; i++) {
            UUID delivery = id("reopen-" + i);
            store.seed(TENANT_A, delivery, DeliveryState.RETRY_WAIT, 1, BASE.plusMillis(i), null);
            circuitDeferred.put(delivery, TARGET_A);
        }
        DeliveryRetryTrigger trigger = halfOpenTrigger(circuitDeferred, 3);

        assertThat(List.of(trigger.runOnce(), trigger.runOnce(), trigger.runOnce())).containsExactly(3, 3, 1);
    }

    @Test
    void theHalfOpenCapIsPerTargetAndNeverCountsDeliveriesThatWereNotCircuitDeferred() {
        Map<UUID, UUID> circuitDeferred = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            UUID a = id("ha-" + i);
            store.seed(TENANT_A, a, DeliveryState.RETRY_WAIT, 1, BASE.plusMillis(i), null);
            circuitDeferred.put(a, TARGET_A);
            UUID b = id("hb-" + i);
            store.seed(TENANT_A, b, DeliveryState.RETRY_WAIT, 1, BASE.plusMillis(i), null);
            circuitDeferred.put(b, TARGET_B);
            store.seed(TENANT_A, id("plain-" + i), DeliveryState.RETRY_WAIT, 2, BASE.plusMillis(i), null);
        }

        int reDriven = halfOpenTrigger(circuitDeferred, 2).runOnce();

        assertThat(reDriven).as("2 per target + 5 ordinary retries").isEqualTo(9);
        assertThat(store.stateOf(id("ha-1"))).isEqualTo(DeliveryState.DUE);
        assertThat(store.stateOf(id("ha-2"))).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(store.stateOf(id("hb-1"))).isEqualTo(DeliveryState.DUE);
        assertThat(store.stateOf(id("hb-2"))).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(store.stateOf(id("plain-4"))).isEqualTo(DeliveryState.DUE);
    }

    @Test
    void aTargetWithNoHalfOpenFactsIsNotCappedAndTheDefaultIsUnlimited() {
        for (int i = 0; i < 4; i++) {
            store.seed(TENANT_A, id("free-" + i), DeliveryState.RETRY_WAIT, 1, BASE.plusMillis(i), null);
        }

        assertThat(trigger(() -> List.of(TENANT_A)).runOnce()).isEqualTo(4);
    }

    // ---------- T-180: the delivery side of the reconciler sweep ----------

    private static final UUID TARGET = id("target");
    private static final UUID TOKEN = id("token");
    private final FakeReconcileStore reconcile = new FakeReconcileStore(context);
    private final Map<UUID, TargetSendFacts.Facts> targetFacts = new HashMap<>();

    @Test
    void aStaleClaimOnANonIdempotentTargetIsDeadLetteredAmbiguous() {
        staleClaim(TENANT_A, D1, 1, Duration.ofMinutes(10));
        targetFacts.put(TARGET, new TargetSendFacts.Facts(Duration.ofSeconds(1), false));

        reconcilingTrigger(() -> List.of(TENANT_A)).runOnce();

        assertThat(store.stateOf(D1)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(store.deadLetterReasonOf(D1)).isEqualTo(OutcomeClass.AMBIGUOUS);
    }

    @Test
    void aStaleClaimOnAnIdempotentTargetIsScheduledForRetryNeverResent() {
        staleClaim(TENANT_A, D1, 1, Duration.ofMinutes(10));
        targetFacts.put(TARGET, new TargetSendFacts.Facts(Duration.ofSeconds(1), true));

        reconcilingTrigger(() -> List.of(TENANT_A)).runOnce();

        assertThat(store.stateOf(D1)).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(store.nextAttemptAtOf(D1)).isAfter(Instant.now().minusSeconds(5));
        assertThat(outbox.committedEntries()).isEmpty(); // re-driven later by the retry, never straight to SENDING
    }

    @Test
    void anIdempotentStaleClaimOutOfAttemptsIsDeadLettered() {
        staleClaim(TENANT_A, D1, RetryPolicy.defaults().maxAttempts(), Duration.ofMinutes(10));
        targetFacts.put(TARGET, new TargetSendFacts.Facts(Duration.ofSeconds(1), true));

        reconcilingTrigger(() -> List.of(TENANT_A)).runOnce();

        assertThat(store.stateOf(D1)).isEqualTo(DeliveryState.DEAD_LETTERED);
    }

    @Test
    void aHealthyClaimInsideItsStaleLimitIsLeftAlone() {
        staleClaim(TENANT_A, D1, 1, Duration.ofSeconds(30)); // limit 3 x 30 s + 5 s + 60 s = 155 s
        targetFacts.put(TARGET, new TargetSendFacts.Facts(Duration.ofSeconds(30), false));

        reconcilingTrigger(() -> List.of(TENANT_A)).runOnce();

        assertThat(store.stateOf(D1)).isEqualTo(DeliveryState.SENDING);
    }

    @Test
    void anUnknownTargetAssumesTheLongestTimeoutBeforeSettling() {
        staleClaim(TENANT_A, D1, 1, Duration.ofMinutes(10)); // inside 3 x 300 s + 65 s
        staleClaim(TENANT_A, D2, 1, Duration.ofMinutes(20)); // past it

        reconcilingTrigger(() -> List.of(TENANT_A)).runOnce();

        assertThat(store.stateOf(D1)).isEqualTo(DeliveryState.SENDING);
        assertThat(store.stateOf(D2)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(store.deadLetterReasonOf(D2)).isEqualTo(OutcomeClass.AMBIGUOUS);
    }

    @Test
    void aHeldDeliveryWhosePredecessorIsFinalIsReleasedAndReDrivenInItsTenant() {
        reconcile.held(TENANT_A, D1);
        reconcile.held(TENANT_B, D2);

        int moved = reconcilingTrigger(() -> List.of(TENANT_A, TENANT_B)).runOnce();

        assertThat(moved).isEqualTo(2);
        assertThat(reconcile.released()).containsExactlyInAnyOrder(
                Tuple.tuple(TENANT_A, D1), Tuple.tuple(TENANT_B, D2));
        assertThat(outbox.committedEntries())
                .extracting(OutboxEntry::topic, OutboxEntry::aggregateId, OutboxEntry::recordKey)
                .containsExactlyInAnyOrder(Tuple.tuple(OutboxTopic.DELIVERY_DUE, D1, "delivery:" + D1),
                        Tuple.tuple(OutboxTopic.DELIVERY_DUE, D2, "delivery:" + D2));
    }

    @Test
    void aHeldDeliveryNoLongerHeldWritesNoDueRecord() {
        reconcile.held(TENANT_A, D1);
        reconcile.refuseRelease(D1);

        int moved = reconcilingTrigger(() -> List.of(TENANT_A)).runOnce();

        assertThat(moved).isZero();
        assertThat(outbox.committedEntries()).isEmpty();
    }

    @Test
    void aStaleClaimOfAnotherTenantIsNeverSettledFromThisTenant() {
        staleClaim(TENANT_B, D1, 1, Duration.ofMinutes(30));

        reconcilingTrigger(() -> List.of(TENANT_A)).runOnce();

        assertThat(store.stateOf(D1)).isEqualTo(DeliveryState.SENDING);
    }

    // ---------- T72: the routing leg of the reconciler sweep, and the delivery ids in the log ----------

    @Test
    void theUnroutedMessageSweepRunsOncePerActiveTenantWithTheTriggerContextUnbound() {
        List<UUID> swept = new ArrayList<>();
        List<Boolean> boundDuringSweep = new ArrayList<>();
        UnroutedMessageSweep sweep = tenant -> {
            swept.add(tenant);
            boundDuringSweep.add(context.currentTenantId().isPresent());
            return 1;
        };

        sweepingTrigger(() -> List.of(TENANT_A, TENANT_B), sweep).runOnce();

        assertThat(swept).as("the routing leg binds its own tenant; the trigger's stays out of its way")
                .containsExactly(TENANT_A, TENANT_B);
        assertThat(boundDuringSweep).containsOnly(false);
    }

    @Test
    void aSweepFailureForOneTenantIsLoggedAndNeverStopsTheNextTenant() {
        store.seed(TENANT_B, D2, DeliveryState.RETRY_WAIT, 1, BASE, null);
        List<UUID> swept = new ArrayList<>();
        UnroutedMessageSweep sweep = tenant -> {
            swept.add(tenant);
            if (tenant.equals(TENANT_A)) {
                throw new IllegalStateException("boom");
            }
            return 0;
        };

        ListAppender<ILoggingEvent> log = capture();
        int moved;
        try {
            moved = sweepingTrigger(() -> List.of(TENANT_A, TENANT_B), sweep).runOnce();
        } finally {
            release(log);
        }

        assertThat(swept).containsExactly(TENANT_A, TENANT_B);
        assertThat(moved).as("tenant B's re-drive still ran").isEqualTo(1);
        assertThat(log.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(TENANT_A.toString());
        });
    }

    @Test
    void aReleasedHeldDeliveryIsLoggedWithItsId() {
        reconcile.held(TENANT_A, D1);

        ListAppender<ILoggingEvent> log = capture();
        try {
            reconcilingTrigger(() -> List.of(TENANT_A)).runOnce();
        } finally {
            release(log);
        }

        assertThat(messages(log)).as("the release of a held delivery names it")
                .anyMatch(m -> m.contains("released") && m.contains(D1.toString()));
    }

    @Test
    void aReHandedUnhandedDeliveryIsLoggedWithItsId() {
        reconcile.unhanded(TENANT_A, D2);

        ListAppender<ILoggingEvent> log = capture();
        try {
            reconcilingTrigger(() -> List.of(TENANT_A)).runOnce();
        } finally {
            release(log);
        }

        assertThat(outbox.committedEntries()).extracting(OutboxEntry::aggregateId).containsExactly(D2);
        assertThat(messages(log)).as("the re-hand of an unhanded delivery names it")
                .anyMatch(m -> m.contains("re-handed") && m.contains(D2.toString()));
    }

    @Test
    void aSettledStaleClaimIsLoggedWithItsId() {
        staleClaim(TENANT_A, D1, 1, Duration.ofMinutes(10));
        targetFacts.put(TARGET, new TargetSendFacts.Facts(Duration.ofSeconds(1), false));

        ListAppender<ILoggingEvent> log = capture();
        try {
            reconcilingTrigger(() -> List.of(TENANT_A)).runOnce();
        } finally {
            release(log);
        }

        assertThat(messages(log)).anyMatch(m -> m.contains("stale claim") && m.contains(D1.toString()));
    }

    private ListAppender<ILoggingEvent> capture() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeliveryRetryTrigger.class))
                .addAppender(appender);
        return appender;
    }

    private void release(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeliveryRetryTrigger.class))
                .detachAppender(appender);
    }

    private static List<String> messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private DeliveryRetryTrigger sweepingTrigger(ActiveTenants tenants, UnroutedMessageSweep sweep) {
        return new DeliveryRetryTrigger(tenants, context, transactions, store, outbox, BATCH,
                InstanceReadiness.alwaysReady(), delivery -> Optional.empty(), TargetRedriveCap.unlimited(),
                reconcile, TargetSendFacts.unknown(), RetryPolicy.defaults(),
                io.integrationplatform.delivery.port.DeliveryCircuitFacts.none(),
                io.integrationplatform.delivery.port.TargetHalfOpenCap.unlimited(), sweep);
    }

    private void staleClaim(UUID tenant, UUID delivery, int attempts, Duration age) {
        store.seed(tenant, delivery, DeliveryState.SENDING, attempts, null, TOKEN);
        reconcile.claim(tenant, new DeliveryReconcileStore.SendingClaim(delivery, TOKEN, Instant.now().minus(age),
                TARGET, attempts));
    }

    private DeliveryRetryTrigger reconcilingTrigger(ActiveTenants tenants) {
        return new DeliveryRetryTrigger(tenants, context, transactions, store, outbox, BATCH,
                InstanceReadiness.alwaysReady(), delivery -> Optional.empty(), TargetRedriveCap.unlimited(),
                reconcile, target -> Optional.ofNullable(targetFacts.get(target)), RetryPolicy.defaults());
    }

    /** The reconcile store contract: the bound tenant's rows only; a refused release is {@code false}. */
    private static final class FakeReconcileStore implements DeliveryReconcileStore {

        private final TriggerTenantContext tenantContext;
        private final Map<UUID, List<SendingClaim>> claims = new HashMap<>();
        private final Map<UUID, List<UUID>> held = new HashMap<>();
        private final Map<UUID, List<UUID>> unhanded = new HashMap<>();
        private final List<UUID> refused = new ArrayList<>();
        private final List<Tuple> released = new CopyOnWriteArrayList<>();

        FakeReconcileStore(TriggerTenantContext tenantContext) {
            this.tenantContext = tenantContext;
        }

        void claim(UUID tenant, SendingClaim claim) {
            claims.computeIfAbsent(tenant, t -> new ArrayList<>()).add(claim);
        }

        void held(UUID tenant, UUID delivery) {
            held.computeIfAbsent(tenant, t -> new ArrayList<>()).add(delivery);
        }

        void unhanded(UUID tenant, UUID delivery) {
            unhanded.computeIfAbsent(tenant, t -> new ArrayList<>()).add(delivery);
        }

        void refuseRelease(UUID delivery) {
            refused.add(delivery);
        }

        List<Tuple> released() {
            return List.copyOf(released);
        }

        @Override
        public List<SendingClaim> sendingClaimedBefore(Instant claimedBefore, int limit) {
            return claims.getOrDefault(tenantContext.requireTenantId(), List.of()).stream()
                    .filter(c -> !c.claimedAt().isAfter(claimedBefore)).limit(limit).toList();
        }

        @Override
        public List<UUID> lockReleasableHeld(int limit) {
            return held.getOrDefault(tenantContext.requireTenantId(), List.of()).stream().limit(limit).toList();
        }

        @Override
        public List<UUID> lockUnhanded(Instant unchangedBefore, int limit) {
            return unhanded.getOrDefault(tenantContext.requireTenantId(), List.of()).stream().limit(limit).toList();
        }

        @Override
        public boolean release(UUID deliveryId) {
            if (refused.contains(deliveryId)) {
                return false;
            }
            released.add(Tuple.tuple(tenantContext.requireTenantId(), deliveryId));
            return true;
        }
    }

    // ---------- helpers ----------

    private DeliveryRetryTrigger trigger(ActiveTenants tenants) {
        return new DeliveryRetryTrigger(tenants, context, transactions, store, outbox, BATCH);
    }

    private DeliveryRetryTrigger halfOpenTrigger(Map<UUID, UUID> circuitDeferred, int trialCalls) {
        return new DeliveryRetryTrigger(() -> List.of(TENANT_A), context, transactions, store, outbox, 1000,
                InstanceReadiness.alwaysReady(), delivery -> Optional.empty(), TargetRedriveCap.unlimited(),
                DeliveryReconcileStore.none(), TargetSendFacts.unknown(), RetryPolicy.defaults(),
                delivery -> Optional.ofNullable(circuitDeferred.get(delivery)), target -> trialCalls);
    }

    private DeliveryRetryTrigger cappedTrigger(Map<UUID, BulkheadFacts> facts, int maxConcurrentSends,
            int instances, int batch) {
        return new DeliveryRetryTrigger(() -> List.of(TENANT_A), context, transactions, store, outbox, batch,
                InstanceReadiness.alwaysReady(), delivery -> Optional.ofNullable(facts.get(delivery)),
                TargetRedriveCap.of(target -> maxConcurrentSends, instances));
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
