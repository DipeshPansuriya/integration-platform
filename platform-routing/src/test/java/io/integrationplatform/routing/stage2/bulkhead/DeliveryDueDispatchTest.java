package io.integrationplatform.routing.stage2.bulkhead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.delivery.DeliveryClaimService;
import io.integrationplatform.delivery.port.BulkheadDeferrals;
import io.integrationplatform.delivery.port.BulkheadRedrive;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts.BulkheadFacts;
import io.integrationplatform.ingest.port.CorrelationContext;
import io.integrationplatform.routing.adapter.api.TargetEndpointView;
import io.integrationplatform.routing.adapter.api.TargetEndpointViews;
import io.integrationplatform.routing.stage.WorkRecord;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * T-177 (E9-F1-S4, ADR-010 section 2 stage 2): the {@code platform.delivery.due} dispatch contract on the
 * partition thread - read the delivery's bulkhead facts, take the target's permit BEFORE the claim, claim
 * through the existing claim order only when a permit is held, hand the send to the target's pool threads,
 * and on a full pool DEFER (never block, never claim): the conditional {@link BulkheadDeferrals#defer} runs and
 * the record is acknowledged. A {@code HELD} or duplicate claim returns the permit at once. No target call is
 * reachable from the partition thread except by hand-off to the target's bulkhead - proven behaviorally here:
 * every dispatch runs on a {@code bulkhead-*} thread while the dispatch itself is driven from the test
 * (partition) thread.
 */
class DeliveryDueDispatchTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    private final WorkerTenantBinding binding = new WorkerTenantBinding();
    private final FakeTransactions transactions = new FakeTransactions();
    private final RecordingFacts facts = new RecordingFacts();
    private final RecordingDeferrals deferrals = new RecordingDeferrals();
    private final RecordingClaim claim = new RecordingClaim();
    private final RecordingSend send = new RecordingSend();
    private final FakeViews views = new FakeViews();
    private final RecordingRedrive redrive = new RecordingRedrive();

    @Test
    @DisplayName("full bulkhead: the record defers BEFORE claiming - no claim, no send, the thread never blocks")
    void fullBulkheadDefersBeforeClaim() {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, "key-1", 2));
            registry.of(target).tryAcquire(); // the pool is already full
            DeliveryDueDispatch dispatch = dispatch(registry);

            dispatch.onWork(deliveryRecord(delivery), TENANT);

            assertThat(deferrals.calls)
                    .as("one deferral with the row's own consecutive count and the injected jitter")
                    .containsExactly(new DeferralCall(delivery, NOW, 2, 0.5));
            assertThat(claim.calls).as("no permit - no claim (ADR-010 section 2)").isEmpty();
            assertThat(send.calls).as("no permit - no target call").isEmpty();
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("a deferral whose conditional update matched 0 rows is acknowledged with no effect")
    void deferralWithNoEffectIsAcknowledged() {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, "key-1", 0));
            deferrals.result = false; // another transaction already claimed or moved it
            registry.of(target).tryAcquire();

            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);

            assertThat(deferrals.calls).hasSize(1);
            assertThat(claim.calls).isEmpty();
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("free bulkhead: permit is held DURING the claim and the send runs on a bulkhead thread")
    void claimHappensWithThePermitHeldAndSendLeavesThePartitionThread() {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, "key-1", 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.CLAIMED);
            claim.permitHeldDuringClaim = () -> !registry.of(target).tryAcquire(); // the permit is ours

            AtomicReference<String> completion = new AtomicReference<>();
            send.onCall = completion::set;
            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);

            assertThat(claim.calls).hasSize(1);
            awaitOneSend(send); // the hand-off ran on the pool - now its thread name is settled
            String poolThread = completion.get();
            assertThat(poolThread).as("the send handed off to the target's pool").isNotNull();
            assertThat(poolThread).startsWith("bulkhead-");
            assertThat(poolThread).isNotEqualTo(Thread.currentThread().getName());
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("the Stage2Send is assembled from the facts, the fresh token and the pinned target declaration")
    void sendCarriesTheFactsAndDeclaration() {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(2);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, "key-9", 1));
            claim.outcomes.add(DeliveryClaimService.Outcome.CLAIMED);
            views.idempotentReceive = true;
            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);

            Stage2Send dispatched = awaitOneSend(send);
            assertThat(dispatched.deliveryId()).isEqualTo(delivery);
            assertThat(dispatched.orderingKey()).isEqualTo("key-9");
            assertThat(dispatched.targetEndpointId()).isEqualTo(target);
            assertThat(dispatched.targetDeclaresIdempotentReceive()).isTrue();
            assertThat(claim.calls.get(0).claimToken()).isEqualTo(dispatched.claimToken());
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("a HELD claim returns the permit at once and hands nothing to the pool")
    void heldClaimReturnsThePermitWithoutDispatch() {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, null, 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.HELD);

            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);

            assertThat(send.calls).isEmpty();
            assertThat(registry.of(target).tryAcquire())
                    .as("the permit is back - the next record for this target admits").isTrue();
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("a duplicate claim (NO_EFFECT) acknowledges: no send, permit returned")
    void duplicateClaimAcknowledgesWithNoEffect() {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, null, 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.NO_EFFECT);

            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);

            assertThat(send.calls).isEmpty();
            assertThat(registry.of(target).tryAcquire()).isTrue();
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("one full target never stalls another sharing the dispatcher - A defers, B sends")
    void targetsAreIsolatedThroughTheDispatcher() {
        UUID targetA = UUID.randomUUID();
        UUID targetB = UUID.randomUUID();
        UUID deliveryA = UUID.randomUUID();
        UUID deliveryB = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            registry.of(targetA).tryAcquire(); // A's bulkhead is full
            facts.byDelivery.put(deliveryA, new BulkheadFacts(targetA, "ka", 0));
            facts.byDelivery.put(deliveryB, new BulkheadFacts(targetB, "kb", 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.CLAIMED);

            DeliveryDueDispatch dispatch = dispatch(registry);
            dispatch.onWork(deliveryRecord(deliveryA), TENANT);
            dispatch.onWork(deliveryRecord(deliveryB), TENANT);

            assertThat(deferrals.calls).containsExactly(new DeferralCall(deliveryA, NOW, 0, 0.5));
            Stage2Send b = awaitOneSend(send);
            assertThat(b.deliveryId()).isEqualTo(deliveryB);
            assertThat(b.targetEndpointId()).isEqualTo(targetB);
            assertThat(claim.calls).hasSize(1); // only B ever claimed
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("a delivery with no readable row is acknowledged with no effect - no permit, no claim")
    void missingFactsAcknowledges() {
        UUID delivery = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.empty();
            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);
            assertThat(claim.calls).isEmpty();
            assertThat(deferrals.calls).isEmpty();
            assertThat(send.calls).isEmpty();
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("B19 F1: the idempotent-receive flag is read under the record's tenant, inside a transaction,"
            + " BEFORE the claim")
    void idempotentReceiveIsReadTenantScopedBeforeTheClaim() {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, "key-1", 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.CLAIMED);
            views.idempotentReceive = true;

            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);

            assertThat(views.reads).as("one endpoint read")
                    .containsExactly(new EndpointRead(target, true, Optional.of(TENANT), 0));
            assertThat(awaitOneSend(send).targetDeclaresIdempotentReceive())
                    .as("the send still carries the pinned declaration").isTrue();
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("B19 F1: a failing endpoint read claims nothing - no CLAIMED/SENDING row is left to strand")
    void aFailingEndpointReadClaimsNothing() {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, "key-1", 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.CLAIMED);
            IllegalStateException refused = new IllegalStateException("A tenant-scoped access must run inside a"
                    + " transaction (R11)");
            views.failure = refused;

            assertThatThrownBy(() -> dispatch(registry).onWork(deliveryRecord(delivery), TENANT))
                    .as("the failure reaches the stage base, which retries the record (TRANSIENT)")
                    .isSameAs(refused);

            assertThat(claim.calls).as("nothing was claimed, so nothing is stranded in SENDING").isEmpty();
            assertThat(send.calls).isEmpty();
            assertThat(registry.of(target).tryAcquire()).as("the permit is back").isTrue();
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("B19 F3: the send runs on the pool thread under the record's tenant, unbound after - nothing"
            + " leaks to that thread's next task")
    void poolThreadRunsUnderTheRecordsTenantAndNothingLeaks() throws Exception {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1); // one pool thread: the next task reuses it
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, "key-1", 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.CLAIMED);
            AtomicReference<String> sendThread = new AtomicReference<>();
            send.onCall = sendThread::set;
            send.tenant = binding::currentTenantId;

            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);
            assertThat(binding.currentTenantId()).as("the partition thread is unbound after the record").isEmpty();

            // queued behind the send on the same single thread, so it runs only once the send's task has ended
            AtomicReference<String> nextThread = new AtomicReference<>();
            AtomicReference<Optional<UUID>> nextTenant = new AtomicReference<>();
            CountDownLatch nextRan = new CountDownLatch(1);
            assertThat(registry.of(target).execute(() -> {
                nextThread.set(Thread.currentThread().getName());
                nextTenant.set(binding.currentTenantId());
                nextRan.countDown();
            })).isTrue();
            assertThat(nextRan.await(5, TimeUnit.SECONDS)).as("the next task ran").isTrue();

            assertThat(send.tenants).as("the send ran with the record's tenant bound on the pool thread")
                    .containsExactly(Optional.of(TENANT));
            assertThat(nextThread.get()).as("the same pool thread").isEqualTo(sendThread.get());
            assertThat(nextTenant.get()).as("no tenant leaks to the thread's next task").isEmpty();
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("E11: the send runs inside the record's journey (its traceparent), and the journey closes with the task")
    void poolThreadRunsInTheRecordsJourneyAndClosesIt() throws Exception {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        BulkheadRegistry registry = new BulkheadRegistry(1); // one pool thread: the next task reuses it
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, "key-1", 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.CLAIMED);
            AtomicReference<String> duringSend = new AtomicReference<>();
            AtomicReference<String> correlationDuringSend = new AtomicReference<>();
            send.onCall = thread -> {
                duringSend.set(CorrelationContext.currentTraceparent());
                correlationDuringSend.set(org.slf4j.MDC.get(CorrelationContext.CORRELATION_ID));
            };

            dispatch(registry).onWork(new WorkRecord(1, "delivery.due", delivery, traceparent), TENANT);
            awaitOneSend(send);

            AtomicReference<String> afterSend = new AtomicReference<>("unset");
            CountDownLatch nextRan = new CountDownLatch(1);
            assertThat(registry.of(target).execute(() -> {
                afterSend.set(CorrelationContext.currentTraceparent());
                nextRan.countDown();
            })).isTrue();
            assertThat(nextRan.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(duringSend.get()).as("the send saw the record's own traceparent").isEqualTo(traceparent);
            assertThat(correlationDuringSend.get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(afterSend.get()).as("the journey closed with the task - nothing leaks to the next one").isNull();
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("this handler is the delivery consumer - a message record is refused, never half-handled")
    void messageRecordsAreRefused() {
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            WorkRecord message = new WorkRecord(1, "message.accepted", UUID.randomUUID(), null);
            assertThatThrownBy(() -> dispatch(registry).onWork(message, TENANT))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("test double: a send is published to calls only after onCall ran - awaitOneSend never sees a half-recorded call")
    void recordingSendPublishesTheCallLast() {
        RecordingSend probe = new RecordingSend();
        AtomicReference<Boolean> visibleDuringOnCall = new AtomicReference<>();
        probe.onCall = thread -> visibleDuringOnCall.set(!probe.calls.isEmpty());

        probe.dispatch(new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "key-1", UUID.randomUUID(), false));

        assertThat(visibleDuringOnCall.get())
                .as("the call must not be visible in calls while onCall still runs (T154 race)").isFalse();
        assertThat(probe.calls).hasSize(1);
    }

    @Test
    @DisplayName("drain fix: a finished send frees its slot and re-drives ONE deferred delivery of the SAME target,"
            + " under the record's tenant, without the retry job")
    void finishedSendRedrivesOneDeferredDeliveryOfTheSameTarget() {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, "key-1", 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.CLAIMED);
            send.onCall = thread -> assertThat(redrive.calls).as("not before the send ends").isEmpty();

            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);
            awaitOneSend(send);
            awaitRedrives(1);

            assertThat(redrive.calls).containsExactly(new RedriveCall(target, Optional.of(TENANT)));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("drain fix: bulkhead full -> deferral -> the running send finishes -> the deferred delivery is"
            + " re-driven at once (no job, no backoff wait)")
    void fullBulkheadDeferralIsRedrivenWhenTheRunningSendFinishes() throws Exception {
        UUID running = UUID.randomUUID();
        UUID deferred = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.byDelivery.put(running, new BulkheadFacts(target, "k1", 0));
            facts.byDelivery.put(deferred, new BulkheadFacts(target, "k2", 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.CLAIMED);
            CountDownLatch inSend = new CountDownLatch(1);
            CountDownLatch finishSend = new CountDownLatch(1);
            send.onCall = thread -> {
                inSend.countDown();
                try {
                    finishSend.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };
            DeliveryDueDispatch dispatch = dispatch(registry);
            dispatch.onWork(deliveryRecord(running), TENANT);
            assertThat(inSend.await(5, TimeUnit.SECONDS)).isTrue();
            dispatch.onWork(deliveryRecord(deferred), TENANT); // the only permit is in use

            assertThat(deferrals.calls).extracting(DeferralCall::deliveryId).containsExactly(deferred);
            assertThat(redrive.calls).as("nothing re-driven while the slot is still taken").isEmpty();

            long freedAt = System.nanoTime();
            finishSend.countDown();
            awaitRedrives(1);

            assertThat(redrive.calls).containsExactly(new RedriveCall(target, Optional.of(TENANT)));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - freedAt)).as("well within ~1 s")
                    .isLessThan(1000);
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("drain fix: a failing re-drive never fails the finished send and never leaks the permit or tenant")
    void aFailingRedriveDoesNotFailTheFinishedSend() throws Exception {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, "key-1", 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.CLAIMED);
            redrive.failure = new IllegalStateException("database down");

            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);
            awaitOneSend(send);
            awaitRedrives(1);

            AtomicReference<Optional<UUID>> nextTenant = new AtomicReference<>();
            CountDownLatch nextRan = new CountDownLatch(1);
            assertThat(registry.of(target).execute(() -> {
                nextTenant.set(binding.currentTenantId());
                nextRan.countDown();
            })).as("the pool thread is still usable").isTrue();
            assertThat(nextRan.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(nextTenant.get()).as("no tenant leaks").isEmpty();
            assertThat(registry.of(target).tryAcquire()).as("the permit came back").isTrue();
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("drain fix: a send that throws still frees the slot - the re-drive still runs")
    void aSendThatThrowsStillRedrives() {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, "key-1", 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.CLAIMED);
            send.onCall = thread -> {
                throw new IllegalStateException("target call blew up");
            };

            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);
            awaitRedrives(1);

            assertThat(redrive.calls).hasSize(1);
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("drain fix: nothing was sent (HELD) - no re-drive, so no cascade is possible")
    void noSendNoRedrive() {
        UUID delivery = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BulkheadRegistry registry = new BulkheadRegistry(1);
        try {
            facts.facts = Optional.of(new BulkheadFacts(target, null, 0));
            claim.outcomes.add(DeliveryClaimService.Outcome.HELD);

            dispatch(registry).onWork(deliveryRecord(delivery), TENANT);

            assertThat(redrive.calls).isEmpty();
        } finally {
            registry.shutdown();
        }
    }

    private void awaitRedrives(int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (redrive.calls.size() < count && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(redrive.calls).as("re-drives").hasSize(count);
    }

    private DeliveryDueDispatch dispatch(BulkheadRegistry registry) {
        return new DeliveryDueDispatch(binding, transactions, facts, registry, claim, deferrals, redrive, send,
                views, () -> NOW, () -> 0.5);
    }

    private static WorkRecord deliveryRecord(UUID deliveryId) {
        return new WorkRecord(1, "delivery.due", deliveryId, null);
    }

    private static Stage2Send awaitOneSend(RecordingSend send) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (send.calls.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(send.calls).as("exactly one dispatched send").hasSize(1);
        return send.calls.get(0);
    }

    // ------------------------------------------------------------------ fakes

    private record DeferralCall(UUID deliveryId, Instant now, int consecutiveDeferrals, double jitter) { }

    private record ClaimCall(String orderingKey, UUID deliveryId, UUID claimToken) { }

    private static final class RecordingFacts implements DeliveryBulkheadFacts {

        Optional<BulkheadFacts> facts = Optional.empty();
        final Map<UUID, BulkheadFacts> byDelivery = new java.util.HashMap<>();

        @Override
        public Optional<BulkheadFacts> forDelivery(UUID deliveryId) {
            BulkheadFacts exact = byDelivery.get(deliveryId);
            return exact == null ? facts : Optional.of(exact);
        }
    }

    private static final class RecordingDeferrals implements BulkheadDeferrals {

        final List<DeferralCall> calls = new ArrayList<>();
        boolean result = true;

        @Override
        public boolean defer(UUID deliveryId, Instant now, int consecutiveDeferrals, double jitter) {
            calls.add(new DeferralCall(deliveryId, now, consecutiveDeferrals, jitter));
            return result;
        }
    }

    private record RedriveCall(UUID targetEndpointId, Optional<UUID> tenant) { }

    private final class RecordingRedrive implements BulkheadRedrive {

        final List<RedriveCall> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        RuntimeException failure;

        @Override
        public boolean redriveOneDeferred(UUID targetEndpointId) {
            calls.add(new RedriveCall(targetEndpointId, binding.currentTenantId()));
            if (failure != null) {
                throw failure;
            }
            return true;
        }
    }

    private static final class RecordingClaim implements Stage2Claim {

        final List<ClaimCall> calls = new ArrayList<>();
        final List<DeliveryClaimService.Outcome> outcomes = new ArrayList<>();
        java.util.function.BooleanSupplier permitHeldDuringClaim = () -> true;

        @Override
        public DeliveryClaimService.Outcome claim(String orderingKey, UUID deliveryId, UUID claimToken) {
            calls.add(new ClaimCall(orderingKey, deliveryId, claimToken));
            assertThat(permitHeldDuringClaim.getAsBoolean())
                    .as("the claim runs while the bulkhead permit is held").isTrue();
            return outcomes.get(calls.size() - 1);
        }
    }

    private static final class RecordingSend implements Stage2SendDispatch {

        final List<Stage2Send> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Optional<UUID>> tenants = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.function.Consumer<String> onCall = thread -> { };
        java.util.function.Supplier<Optional<UUID>> tenant = Optional::empty;

        @Override
        public void dispatch(Stage2Send send) {
            tenants.add(tenant.get());
            onCall.accept(Thread.currentThread().getName());
            calls.add(send); // published last: awaitOneSend returning means onCall already ran (T154)
        }
    }

    /** What the endpoint read saw: inside a transaction, the bound tenant, how many claims had already run. */
    private record EndpointRead(UUID endpointId, boolean inTransaction, Optional<UUID> tenant, int claimsBefore) { }

    /** Marks a transaction open on the calling thread for the callback's duration - no database. */
    private static final class FakeTransactions implements TransactionOperations {

        final ThreadLocal<Boolean> open = ThreadLocal.withInitial(() -> false);

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            open.set(true);
            try {
                return action.doInTransaction(new SimpleTransactionStatus());
            } finally {
                open.set(false);
            }
        }
    }

    /** The JdbcTargetEndpointViews stand-in: records the context of each read, as the tenant-scoped base checks it. */
    private final class FakeViews implements TargetEndpointViews {

        boolean idempotentReceive;
        RuntimeException failure;
        final List<EndpointRead> reads = new ArrayList<>();

        @Override
        public Optional<TargetEndpointView> byEndpointId(UUID endpointId) {
            reads.add(new EndpointRead(endpointId, transactions.open.get(), binding.currentTenantId(),
                    claim.calls.size()));
            if (failure != null) {
                throw failure;
            }
            return Optional.of(new TargetEndpointView(URI.create("http://target.test"), "POST", 5000,
                    "NO_AUTH", idempotentReceive, Map.of()));
        }
    }
}
