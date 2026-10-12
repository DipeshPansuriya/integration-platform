package io.integrationplatform.routing.credential;

import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.api.SealedCredential;
import io.integrationplatform.config.credential.api.TargetCredentialVault;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Decrypt only at send time (ADR-007 Decision 7 rule 6; T-185): reads a target endpoint's sealed rows through the
 * configuration module's vault (in the caller's tenant-bound transaction) and opens every field with this instance's
 * key-encryption keys. Nothing is cached - every send reads and opens again - so "only at send time" holds. One field
 * that fails fails the whole open (KeyUnavailableException or CredentialUnreadableException) and the fields already
 * opened are wiped.
 */
public final class SendTimeCredentials {

    private final TargetCredentialVault vault;
    private final KeyEncryptionKeys keys;

    public SendTimeCredentials(TargetCredentialVault vault, KeyEncryptionKeys keys) {
        this.vault = Objects.requireNonNull(vault, "vault");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /** Every credential value of the endpoint, for this call only (close it). */
    public OpenedCredentials open(UUID targetEndpointId) {
        Map<String, char[]> values = new LinkedHashMap<>();
        try {
            for (SealedCredential sealed : vault.sealedFor(targetEndpointId)) {
                values.put(sealed.field(), EnvelopeOpener.open(sealed, keys));
            }
            return new OpenedCredentials(values);
        } catch (RuntimeException failed) {
            values.values().forEach(value -> Arrays.fill(value, '\0'));
            throw failed;
        }
    }
}
