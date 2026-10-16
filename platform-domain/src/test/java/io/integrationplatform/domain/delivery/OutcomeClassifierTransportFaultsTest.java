package io.integrationplatform.domain.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three transport faults no HTTP answer classifies (E10 narrow, T-050): the adapter must never map an
 * exception itself - the classifier owns every class (ADR-015, ADR-010 section 5). The register follows the
 * 408/5xx family already decided above: a timeout is ambiguous (the request may have been processed) unless the
 * target declares idempotent receive; a connect failure sent nothing, so it is transient; a destination refusal
 * is the configuration's fault - dead-lettered, never retried.
 */
class OutcomeClassifierTransportFaultsTest {

    @Test
    @DisplayName("a read timeout is AMBIGUOUS - the request may have been processed")
    void timeoutIsAmbiguousWithoutIdempotentReceive() {
        assertThat(OutcomeClassifier.ofTimeout(false)).isEqualTo(OutcomeClass.AMBIGUOUS);
    }

    @Test
    @DisplayName("a read timeout is TRANSIENT when the target receives idempotently")
    void timeoutIsTransientWithIdempotentReceive() {
        assertThat(OutcomeClassifier.ofTimeout(true)).isEqualTo(OutcomeClass.TRANSIENT);
    }

    @Test
    @DisplayName("a connect failure is TRANSIENT - nothing was sent, the try may repeat")
    void connectFailureIsTransient() {
        assertThat(OutcomeClassifier.ofConnectFailure()).isEqualTo(OutcomeClass.TRANSIENT);
    }

    @Test
    @DisplayName("a destination refusal is PERMANENT - only the configuration can fix it")
    void destinationRefusalIsPermanent() {
        assertThat(OutcomeClassifier.ofDestinationRefusal()).isEqualTo(OutcomeClass.PERMANENT);
    }
}