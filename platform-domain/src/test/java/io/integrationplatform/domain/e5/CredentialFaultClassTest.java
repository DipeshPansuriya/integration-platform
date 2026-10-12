package io.integrationplatform.domain.e5;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.domain.delivery.OutcomeClassifier;
import io.integrationplatform.domain.delivery.OutcomeClassifier.CredentialFault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E5-F1-S4 (T-185; ADR-007 Decision 7 rule 5, ADR-010 section 5): the two decryption failures of a target credential
 * are told apart in the ONE classifier - a key-encryption-key id this instance does not hold is a deployment fault,
 * fixable without touching the row (TRANSIENT: wait, another instance or a redeploy sends it); an authentication-tag
 * mismatch is tampering or a copied row (PERMANENT: dead-letter, never retried).
 */
class CredentialFaultClassTest {

    @Test
    @DisplayName("Unknown key-encryption-key id is TRANSIENT")
    void unknownKeyIsTransient() {
        assertThat(OutcomeClassifier.ofCredentialFault(CredentialFault.KEY_UNAVAILABLE))
                .isEqualTo(OutcomeClass.TRANSIENT);
    }

    @Test
    @DisplayName("Tag mismatch is PERMANENT")
    void tagMismatchIsPermanent() {
        assertThat(OutcomeClassifier.ofCredentialFault(CredentialFault.TAG_MISMATCH))
                .isEqualTo(OutcomeClass.PERMANENT);
    }

    @Test
    @DisplayName("The register of credential faults is closed: exactly the two cases of ADR-007 Decision 7 rule 5")
    void exactlyTwoFaults() {
        assertThat(CredentialFault.values()).containsExactly(CredentialFault.KEY_UNAVAILABLE,
                CredentialFault.TAG_MISMATCH);
    }
}
