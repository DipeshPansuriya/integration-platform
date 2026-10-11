package io.integrationplatform.config.credential.api;

import java.util.Objects;

/**
 * A target credential names a key-encryption-key id this instance does not hold (ADR-007 Decision 7 rule 5): a
 * deployment fault, TRANSIENT - no call is made, no attempt is used up, the delivery waits and the instance is pulled
 * out of service. Carries the key id only (an id is not a secret).
 */
public final class KeyUnavailableException extends RuntimeException {

    private final String kekId;

    public KeyUnavailableException(String kekId) {
        super("The key-encryption key " + kekId + " is not on this instance");
        this.kekId = Objects.requireNonNull(kekId, "kekId");
    }

    public String kekId() {
        return kekId;
    }
}
