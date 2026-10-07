package io.integrationplatform.domain.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-100 (E4-F1-S4 "401 and 403 are never retried"): the ONE HTTP-status classifier every send adapter uses (ADR-010
 * section 5 default table; ADR-007 Decision 4 "401 and 403 are final"). A target's error dictionary may re-class a
 * status, but never 401, 403 or a validation error (400, 422).
 */
class OutcomeClassifierTest {

    @ParameterizedTest(name = "{0} is PERMANENT whatever the target declares")
    @ValueSource(ints = {401, 403})
    @DisplayName("@negative @security: 401 and 403 are PERMANENT - never retried in either layer")
    void refusalsArePermanent(int status) {
        assertThat(OutcomeClassifier.ofHttpStatus(status, false)).isEqualTo(OutcomeClass.PERMANENT);
        assertThat(OutcomeClassifier.ofHttpStatus(status, true)).isEqualTo(OutcomeClass.PERMANENT);
    }

    @ParameterizedTest(name = "an error dictionary cannot make {0} retryable")
    @ValueSource(ints = {400, 401, 403, 422})
    void anErrorDictionaryCannotMakeARefusalOrAValidationErrorRetryable(int status) {
        for (OutcomeClass override : OutcomeClass.values()) {
            assertThat(OutcomeClassifier.ofHttpStatus(status, true, Map.of(status, override)))
                    .as("override %s for %d", override, status)
                    .isEqualTo(OutcomeClass.PERMANENT);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422, 405, 410})
    void otherClientErrorsArePermanent(int status) {
        assertThat(OutcomeClassifier.ofHttpStatus(status, true)).isEqualTo(OutcomeClass.PERMANENT);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204})
    void twoHundredsAreSuccess(int status) {
        assertThat(OutcomeClassifier.ofHttpStatus(status, false)).isEqualTo(OutcomeClass.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void throttledAndUnavailableAreTransient(int status) {
        assertThat(OutcomeClassifier.ofHttpStatus(status, false)).isEqualTo(OutcomeClass.TRANSIENT);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 500, 502, 504})
    void mayHaveBeenProcessedIsAmbiguousUnlessTheTargetDeclaresIdempotentReceive(int status) {
        assertThat(OutcomeClassifier.ofHttpStatus(status, false)).isEqualTo(OutcomeClass.AMBIGUOUS);
        assertThat(OutcomeClassifier.ofHttpStatus(status, true)).isEqualTo(OutcomeClass.TRANSIENT);
    }

    @Test
    void anErrorDictionaryMayReclassAnyOtherStatus() {
        assertThat(OutcomeClassifier.ofHttpStatus(500, false, Map.of(500, OutcomeClass.TRANSIENT)))
                .isEqualTo(OutcomeClass.TRANSIENT);
        assertThat(OutcomeClassifier.ofHttpStatus(404, false, Map.of(404, OutcomeClass.TRANSIENT)))
                .isEqualTo(OutcomeClass.TRANSIENT);
    }

    @Test
    void anUnexpectedStatusIsNeverTreatedAsDelivered() {
        assertThat(OutcomeClassifier.ofHttpStatus(302, true)).isEqualTo(OutcomeClass.PERMANENT);
        assertThat(OutcomeClassifier.ofHttpStatus(501, false)).isEqualTo(OutcomeClass.AMBIGUOUS);
    }
}
