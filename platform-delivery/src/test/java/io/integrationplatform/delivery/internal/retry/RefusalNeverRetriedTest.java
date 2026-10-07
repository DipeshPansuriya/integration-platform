package io.integrationplatform.delivery.internal.retry;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.domain.delivery.OutcomeClassifier;
import io.integrationplatform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-100 (E4-F1-S4 @negative @security "401 and 403 are never retried"), layer 2 at the contract level: the status is
 * classified by the one classifier, the recorder dead-letters it on the FIRST attempt (even with attempts left and a
 * Retry-After), and the delivery-retry job's lock query never selects it - not now, not in a year. The container
 * sibling ({@code RefusalNeverRetriedContainerTest}) proves the same on the real tables with the real trigger.
 */
class RefusalNeverRetriedTest {

    private static final UUID TENANT = id("tenant-a");
    private static final UUID DELIVERY = id("delivery-401");
    private static final UUID CLAIM_TOKEN = id("claim-401");
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final TenantContext tenantContext = () -> Optional.of(TENANT);
    private final FakeDeliveryRetryStore store = new FakeDeliveryRetryStore(tenantContext);
    private final OutcomeRecorder recorder = new OutcomeRecorder(store, RetryPolicy.defaults(), () -> 0.0);

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {401, 403})
    @DisplayName("401/403: PERMANENT, dead-lettered on attempt 1, never selected by the retry job")
    void aRefusalIsDeadLetteredOnTheFirstAttemptAndNeverSelectedAgain(int status) {
        store.seed(TENANT, DELIVERY, DeliveryState.SENDING, 1, null, CLAIM_TOKEN);
        OutcomeClass outcome = OutcomeClassifier.ofHttpStatus(status, true);

        store.beginTransaction();
        recorder.record(DELIVERY, CLAIM_TOKEN, 1, outcome, NOW, Duration.ofSeconds(1), true);
        store.commitTransaction();

        assertThat(outcome).isEqualTo(OutcomeClass.PERMANENT);
        assertThat(store.stateOf(DELIVERY)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(store.deadLetterReasonOf(DELIVERY)).isEqualTo(OutcomeClass.PERMANENT);
        assertThat(store.nextAttemptAtOf(DELIVERY)).as("no retry time is ever scheduled").isNull();

        store.beginTransaction();
        assertThat(store.lockDue(NOW.plus(Duration.ofDays(365)), 1000))
                .as("the delivery-retry job never selects a dead-lettered refusal")
                .isEmpty();
        store.commitTransaction();
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
