package io.integrationplatform.config.credential.api;

import java.util.Objects;
import java.util.UUID;

/**
 * A target credential's authentication tag does not match (ADR-007 Decision 7 rule 5): the ciphertext, its associated
 * data or the key is wrong - tampering or a row copied from elsewhere. PERMANENT: the delivery is dead-lettered with
 * the reason "target credential unreadable". Carries the credential id only; never ciphertext or plaintext, and no
 * cause (a JDK crypto exception text is not echoed).
 */
public final class CredentialUnreadableException extends RuntimeException {

    /** The dead-letter reason of ADR-007 Decision 7 rule 5. */
    public static final String REASON = "target credential unreadable";

    private final UUID credentialId;

    public CredentialUnreadableException(UUID credentialId) {
        super(REASON + ": " + credentialId);
        this.credentialId = Objects.requireNonNull(credentialId, "credentialId");
    }

    public UUID credentialId() {
        return credentialId;
    }
}
