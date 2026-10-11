package io.integrationplatform.config.credential.api;

import java.util.Optional;
import javax.crypto.SecretKey;

/**
 * The deployment's key-encryption keys (ADR-007 Decision 7 rule 1): read only from the secret store through the
 * environment-variable names {@code PLATFORM_CREDENTIAL_KEK_<ID>} (32 bytes, base64), the sealing one named by
 * {@code PLATFORM_CREDENTIAL_KEK_ACTIVE}. Never from a file, a table or a log; held in memory only.
 */
public interface KeyEncryptionKeys {

    /** The key of this id, or empty when this instance was deployed without it (a deployment fault). */
    Optional<SecretKey> find(String kekId);

    /** The id new values are sealed with, or empty when none is configured (saving answers 503). */
    Optional<String> activeId();
}
