package io.integrationplatform.delivery.internal.retry;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T-090 (E2-F2-S3 @negative, S4): how a finished attempt moves the delivery, through lane C's
 * {@link io.integrationplatform.delivery.port.DeliveryRetryStore} contract only - permanent failures die straight
 * away and are never retried; transient ones take the next jittered backoff step; exhaustion and ambiguous
 * outcomes against a target that does not declare idempotent receive end in the dead letter (ADR-010 sections
 * 4-6). The recorder itself changes no state: every store answer of {@code false} (0 rows) is accepted silently,
 * because the store has already recorded the {@code LATE_OUTCOME} event.
 */
class OutcomeRecorderTest {

    private static final UUID TENANT = UUID.nameUUIDFromBytes("tenant-a".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID DELIVERY = UUID.nameUUIDFromBytes("delivery-1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID CLAIM_TOKEN = UUID.nameUUIDFromBytes("claim-1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final TenantContext tenantContext = () -> Optional.of(TENANT);
    private final FakeDeliveryRetryStore store = new FakeDeliveryRetryStore(tenantContext);
    private final OutcomeRecorder recorder = new OutcomeRecorder(store, RetryPolicy.defaults(), () -> 1.0);

    @BeforeEach
    void aSendingDeliveryUnderAnOpenTenantTransaction() {
        store.seed(TENANT, DELIVERY, DeliveryState.SENDING, 1, null, CLAIM_TOKEN);
        store.beginTransaction();
    }

    @Test
    void aSuccessIsNotTheRecordersMove() {
        recorder.record(DELIVERY, CLAIM_TOKEN, 1, OutcomeClass.SUCCESS, NOW, null, false);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY))
                .as("the classifier moves SENDING -> DELIVERED; the recorder leaves it alone")
                .isEqualTo(DeliveryState.SENDING);
        assertThat(store.lateOutcomes()).isZero();
    }

    @Test
    void aPermanentFailureGoesStraightToTheDeadLetterAndIsNeverRetried() {
        recorder.record(DELIVERY, CLAIM_TOKEN, 1, OutcomeClass.PERMANENT, NOW, null, false);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(store.deadLetterReasonOf(DELIVERY)).isEqualTo(OutcomeClass.PERMANENT);
    }

    @Test
    void aTransientFailureSchedulesTheNextRetryWithTheJitteredBackoff() {
        recorder.record(DELIVERY, CLAIM_TOKEN, 1, OutcomeClass.TRANSIENT, NOW, null, false);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY)).isEqualTo(DeliveryState.RETRY_WAIT);
    }

    @Test
    void theNextRetryTimeIsTheBackoffStepForTheAttemptCount() {
        recorder.record(DELIVERY, CLAIM_TOKEN, 2, OutcomeClass.TRANSIENT, NOW, null, true);
        store.commitTransaction();

        // attempt 3 of the growing sequence: 30 s x 2^2 = 120 s at full delay (jitter source pinned to 1.0)
        assertThat(store.nextAttemptAtOf(DELIVERY)).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void anExhaustedTransientSeriesEndsInTheDeadLetterWithTheReasonClass() {
        recorder.record(DELIVERY, CLAIM_TOKEN, 8, OutcomeClass.TRANSIENT, NOW, null, false);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(store.deadLetterReasonOf(DELIVERY)).isEqualTo(OutcomeClass.TRANSIENT);
    }

    @Test
    void anAmbiguousOutcomeAgainstANonIdempotentTargetIsDeadLetteredNotRetried() {
        recorder.record(DELIVERY, CLAIM_TOKEN, 1, OutcomeClass.AMBIGUOUS, NOW, null, false);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(store.deadLetterReasonOf(DELIVERY)).isEqualTo(OutcomeClass.AMBIGUOUS);
    }

    @Test
    void anAmbiguousOutcomeAgainstAnIdempotentTargetWaitsForTheNextRetry() {
        recorder.record(DELIVERY, CLAIM_TOKEN, 1, OutcomeClass.AMBIGUOUS, NOW, Duration.ofSeconds(90), true);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY)).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(store.nextAttemptAtOf(DELIVERY))
                .as("Retry-After beats the 60 s second-step backoff (ADR-010 section 4)")
                .isEqualTo(NOW.plusSeconds(90));
    }

    @Test
    void aStaleOrForeignClaimTokenChangesNothingAndTheStoreRecordsTheLateOutcome() {
        UUID otherToken = UUID.nameUUIDFromBytes("claim-2".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        recorder.record(DELIVERY, otherToken, 1, OutcomeClass.TRANSIENT, NOW, null, false);
        store.commitTransaction();

        assertThat(store.stateOf(DELIVERY)).as("no state change for a claim already overtaken")
                .isEqualTo(DeliveryState.SENDING);
        assertThat(store.lateOutcomes()).as("the store wrote the LATE_OUTCOME event").isEqualTo(1);
    }
}
