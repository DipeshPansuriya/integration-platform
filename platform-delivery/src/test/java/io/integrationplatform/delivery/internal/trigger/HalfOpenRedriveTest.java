package io.integrationplatform.delivery.internal.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.delivery.internal.retry.FakeDeliveryRetryStore;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.delivery.port.DeliveryBulkheadFacts.BulkheadFacts;
import io.integrationplatform.delivery.port.InstanceReadiness;
import io.integrationplatform.delivery.port.TargetBreakerFacts;
import io.integrationplatform.delivery.port.TargetBreakerFacts.BreakerFacts;
import io.integrationplatform.delivery.port.TargetRedriveCap;
import io.integrationplatform.domain.delivery.DeliveryState;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * T-183 (E10-F1-S5, ADR-010 section 6 last row and section 8; challenge S-i): when a target's breaker is not
 * CLOSED, one pass of the delivery-retry job moves at most the target's {@code halfOpenTrialCalls} waiting
 * deliveries to {@code DUE} - the rest stay in {@code RETRY_WAIT}. A4 reads the re-drive cap onto EVERY pass, so
 * a breaker that re-opens before it closes re-drives at most the trial count again on the next pass instead of
 * leaving the remainder waiting forever on a target that never flips back. Pure unit level: the fake store
 * honours the {@code DeliveryRetryStore} contract; the real breaker registry is a routing seam. Not tagged
 * container (R03).
 */
class HalfOpenRedriveTest {

    private static final UUID TENANT = id("t1");
    private static final UUID TARGET = id("target");
    private static final UUID OTHER_TARGET = id("other-target");
    private static final Instant BASE = Instant.parse("2026-10-05T12:00:00Z").minus(Duration.ofDays(1));
    private static final int TRIALS = 3;

    private final TriggerTenantContext context = new TriggerTenantContext();
    private final FakeDeliveryRetryStore store = new FakeDeliveryRetryStore(context);
    private final Map<UUID, UUID> targetByDelivery = new HashMap<>();

    /** The seeded deliveries' pinned target endpoint - the seam the trigger reads its cap key from. */
    private final DeliveryBulkheadFacts facts = deliveryId -> Optional.ofNullable(targetByDelivery.get(deliveryId))
            .map(target -> new BulkheadFacts(target, null, 0));

    private DeliveryRetryTrigger trigger(TargetBreakerFacts breaker) {
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> callback) {
                store.beginTransaction();
                try {
                    T result = callback.doInTransaction(null);
                    store.commitTransaction();
                    return result;
                } catch (RuntimeException failure) {
                    store.rollbackTransaction();
                    throw failure;
                }
            }
        };
        return new DeliveryRetryTrigger(() -> List.of(TENANT), context, transactions, store,
                entry -> 1L, 100, InstanceReadiness.alwaysReady(), facts,
                TargetRedriveCap.unlimited(), breaker);
    }

    @Test
    @DisplayName("50 waiting, breaker half-open, trial count 3: exactly 3 move to DUE, 47 keep waiting")
    void halfOpenReDrivesOnlyTheTrialCount() {
        List<UUID> waiting = seed("d", 50);

        int moved = trigger(target -> Optional.of(new BreakerFacts(BreakerFacts.State.HALF_OPEN, TRIALS)))
                .runOnce();

        assertThat(moved).isEqualTo(TRIALS);
        assertThat(waiting).filteredOn(d -> store.stateOf(d) == DeliveryState.DUE).hasSize(TRIALS);
        assertThat(waiting).filteredOn(d -> store.stateOf(d) == DeliveryState.RETRY_WAIT).hasSize(47);
        assertThat(waiting.subList(0, TRIALS)).as("oldest retry time first")
                .allMatch(d -> store.stateOf(d) == DeliveryState.DUE);
    }

    @Test
    @DisplayName("A breaker that re-opened before closing: the next pass re-drives at most the trial count again (A4)")
    void reOpenedBreakerKeepsRedrivingTheTrialCountPerPass() {
        List<UUID> waiting = seed("d", 50);
        TargetBreakerFacts open = target -> Optional.of(new BreakerFacts(BreakerFacts.State.OPEN, TRIALS));
        DeliveryRetryTrigger trigger = trigger(open);

        assertThat(trigger.runOnce()).isEqualTo(TRIALS);
        assertThat(trigger.runOnce()).as("a second pass on a still-open breaker").isEqualTo(TRIALS);
        assertThat(waiting).filteredOn(d -> store.stateOf(d) == DeliveryState.DUE).hasSize(2 * TRIALS);
        assertThat(waiting).filteredOn(d -> store.stateOf(d) == DeliveryState.RETRY_WAIT).hasSize(44);
    }

    @Test
    @DisplayName("Closed breaker: the breaker caps nothing - every due delivery is re-driven")
    void closedBreakerCapsNothing() {
        List<UUID> waiting = seed("d", 50);
        TargetBreakerFacts closed = target -> Optional.of(new BreakerFacts(BreakerFacts.State.CLOSED, TRIALS));

        assertThat(trigger(closed).runOnce()).isEqualTo(50);
    }

    @Test
    @DisplayName("The cap is per target: another target's waiting deliveries are never counted against it")
    void capIsPerTarget() {
        List<UUID> blocked = seed("blocked", 50);
        List<UUID> free = seed("free", 5);
        free.forEach(d -> targetByDelivery.put(d, OTHER_TARGET));

        int moved = trigger(target -> target.equals(OTHER_TARGET)
                ? Optional.empty()
                : Optional.of(new BreakerFacts(BreakerFacts.State.HALF_OPEN, TRIALS))).runOnce();

        assertThat(moved).as("3 of the blocked target + the 5 of the free one").isEqualTo(TRIALS + 5);
        assertThat(blocked).filteredOn(d -> store.stateOf(d) == DeliveryState.RETRY_WAIT).hasSize(47);
        assertThat(free).allMatch(d -> store.stateOf(d) == DeliveryState.DUE);
    }

    @Test
    @DisplayName("No breaker facts at all: the trigger behaves exactly as before today's change")
    void unknownBreakerChangesNothing() {
        List<UUID> waiting = seed("d", 50);

        assertThat(trigger(TargetBreakerFacts.unknown()).runOnce()).isEqualTo(50);
    }

    /** Seeds {@code count} due RETRY_WAIT rows on the unblocked target, oldest first by the stored retry time. */
    private List<UUID> seed(String namePrefix, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID delivery = id(namePrefix + "-" + i);
            store.seed(TENANT, delivery, DeliveryState.RETRY_WAIT, 1, BASE.plusMillis(i), null);
            targetByDelivery.put(delivery, TARGET);
            ids.add(delivery);
        }
        return ids;
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}