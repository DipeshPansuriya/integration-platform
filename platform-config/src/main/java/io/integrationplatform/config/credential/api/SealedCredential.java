package io.integrationplatform.config.credential.api;

import java.util.Objects;
import java.util.UUID;

/**
 * One stored target credential as the send step reads it: the row's identity (the associated data is built from it)
 * and its two sealed blobs. Holds ciphertext only - never a value - and prints neither blob.
 *
 * @param field the register name of the field ({@link TargetCredentialField#name()}), e.g. {@code BASIC_PASSWORD}
 */
public record SealedCredential(UUID id, UUID tenantId, UUID targetEndpointId, String field, String kekId,
        byte[] wrappedKey, byte[] ciphertext) {

    public SealedCredential {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(targetEndpointId, "targetEndpointId");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(kekId, "kekId");
        Objects.requireNonNull(wrappedKey, "wrappedKey");
        Objects.requireNonNull(ciphertext, "ciphertext");
    }

    @Override
    public String toString() {
        return "SealedCredential[" + id + ", " + field + ", kek " + kekId + "]";
    }
}
