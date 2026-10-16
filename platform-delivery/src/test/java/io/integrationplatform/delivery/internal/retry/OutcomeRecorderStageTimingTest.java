package io.integrationplatform.delivery.internal.retry;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T105 (T-136 data side, E13-F1-S1): FAILED records on the stage that failed. The recorder's dead-letter moves
 * (PERMANENT, a non-idempotent AMBIGUOUS, an exhausted TRANSIENT series) each hand the delivery to the stage timing
 * log exactly once, finished at the failure instant, in the recorder's own (the caller's) transaction; a scheduled
 * retry is not a finish, a stale claim moved nothing, and SUCCESS is the classifier's move, not the recorder's.
 * The real rows (one per message, the rollback, the tenant) are proven by platform-routing's
 * {@code StageTimingCommitContainerTest}.
 */
class OutcomeRecorderStageTimingTest {

    private static final UUID TENANT = uuid("tenant-a");
    private static final UUID DELIVERY = uuid("delivery-1");
    private static final UUID CLAIM_TOKEN = uuid("claim-1");
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    private final TenantContext tenantContext = () -> Optional.of(TENANT);
    private final FakeDeliveryRetryStore store = new FakeDeliveryRetryStore(tenantContext);
    private final RecordingTimings timings = new RecordingTimings();
    private final OutcomeRecorder recorder = new OutcomeRecorder(store, RetryPolicy.defaults(), () -> 1.0, timings);

    @BeforeEach
    void aSendingDeliveryUnderAnOpenTenantTransaction() {
        store.seed(TENANT, DELIVERY, DeliveryState.SENDING, 1, null, CLAIM_TOKEN);
        store.beginTransaction();
    }

    @Test
    void aPermanentDeadLetterRecordsTheDeliverStageOnceFinishedAtTheFailure() {
        recorder.record(DELIVERY, CLAIM_TOKEN, 1, OutcomeClass.PERMANENT, NOW, null, false);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(timings.deliverFinished).containsExactly(DELIVERY + "@" + NOW);
    }

    @Test
    void anAmbiguousDeadLetterAgainstANonIdempotentTargetRecordsTheDeliverStageOnce() {
        recorder.record(DELIVERY, CLAIM_TOKEN, 1, OutcomeClass.AMBIGUOUS, NOW, null, false);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(timings.deliverFinished).containsExactly(DELIVERY + "@" + NOW);
    }

    @Test
    void anExhaustedTransientSeriesRecordsTheDeliverStageOnce() {
        recorder.record(DELIVERY, CLAIM_TOKEN, RetryPolicy.defaults().maxAttempts(), OutcomeClass.TRANSIENT, NOW,
                null, false);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(timings.deliverFinished).containsExactly(DELIVERY + "@" + NOW);
    }

    @Test
    void aScheduledRetryIsNotAFinishAndRecordsNothing() {
        recorder.record(DELIVERY, CLAIM_TOKEN, 1, OutcomeClass.TRANSIENT, NOW, null, false);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY)).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(timings.deliverFinished).as("the delivery stage is still running").isEmpty();
    }

    @Test
    void aStaleClaimMovedNothingAndRecordsNothing() {
        recorder.record(DELIVERY, uuid("claim-2"), 1, OutcomeClass.PERMANENT, NOW, null, false);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY)).isEqualTo(DeliveryState.SENDING);
        assertThat(timings.deliverFinished).as("no dead letter happened, so no failure is timed").isEmpty();
    }

    @Test
    void aSuccessIsTheClassifiersMoveAndTheRecorderRecordsNothing() {
        recorder.record(DELIVERY, CLAIM_TOKEN, 1, OutcomeClass.SUCCESS, NOW, null, false);
        store.commitTransaction();

        assertThat(timings.deliverFinished).isEmpty();
        assertThat(timings.records).isEmpty();
    }

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** Records every call; the real writer is proven on the real tables. */
    private static final class RecordingTimings implements StageTimings {

        final List<String> records = new ArrayList<>();
        final List<String> deliverFinished = new ArrayList<>();

        @Override
        public void record(UUID messageId, Stage stage, Instant startedAt, Instant finishedAt) {
            records.add(messageId + ":" + stage);
        }

        @Override
        public void recordDeliverFinished(UUID deliveryId, Instant finishedAt) {
            deliverFinished.add(deliveryId + "@" + finishedAt);
        }
    }
}
