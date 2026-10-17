package io.integrationplatform.routing.stage2.bulkhead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.delivery.DeliveryClaimService;
import io.integrationplatform.delivery.port.BulkheadDeferrals;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts.BulkheadFacts;
import io.integrationplatform.routing.adapter.api.TargetEndpointView;
import io.integrationplatform.routing.adapter.api.TargetEndpointViews;
import io.integrationplatform.routing.stage.WorkRecord;
import io.integrationplatform.routing.stage2.Stage2Send;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private final RecordingFacts facts = new RecordingFacts();
    private final RecordingDeferrals deferrals = new RecordingDeferrals();
    private final RecordingClaim claim = new RecordingClaim();
    private final RecordingSend send = new RecordingSend();
    private final FakeViews views = new FakeViews();

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

    private DeliveryDueDispatch dispatch(BulkheadRegistry registry) {
        return new DeliveryDueDispatch(facts, registry, claim, deferrals, send, views, () -> NOW, () -> 0.5);
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
        java.util.function.Consumer<String> onCall = thread -> { };

        @Override
        public void dispatch(Stage2Send send) {
            onCall.accept(Thread.currentThread().getName());
            calls.add(send); // published last: awaitOneSend returning means onCall already ran (T154)
        }
    }

    private static final class FakeViews implements TargetEndpointViews {

        boolean idempotentReceive;

        @Override
        public Optional<TargetEndpointView> byEndpointId(UUID endpointId) {
            return Optional.of(new TargetEndpointView(URI.create("http://target.test"), "POST", 5000,
                    "NO_AUTH", idempotentReceive, Map.of()));
        }
    }
}
