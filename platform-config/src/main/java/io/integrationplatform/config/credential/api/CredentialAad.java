package io.integrationplatform.config.credential.api;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * The envelope format of a target credential (ADR-007 Decision 7 rules 1-3), shared by the sealing side (this module,
 * on save) and the opening side (platform-routing, at send time). Both blobs are standard AES-256-GCM as the JDK does
 * it: {@value #NONCE_BYTES}-byte random nonce, then the ciphertext with its {@value #TAG_BITS}-bit tag appended.
 *
 * <ul>
 *   <li>value: encrypted with the credential's own 256-bit data key; associated data
 *       {@code tc:v1|<tenant>|<target endpoint>|<field>};</li>
 *   <li>wrapped key: the data key encrypted with the key-encryption key; associated data
 *       {@code dek:v1|<tenant>|<credential id>|<kek id>}.</li>
 * </ul>
 * A ciphertext copied to another tenant's, endpoint's or field's row - or a wrapped key re-labelled to another
 * credential or key id - fails its tag.
 */
public final class CredentialAad {

    /** GCM nonce length: 96 bits, random per encryption. */
    public static final int NONCE_BYTES = 12;

    /** GCM authentication tag length. */
    public static final int TAG_BITS = 128;

    /** AES-256: data keys and key-encryption keys alike. */
    public static final int KEY_BYTES = 32;

    /** The JDK transformation of every envelope blob. */
    public static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private CredentialAad() {
    }

    /** {@code tc:v1|<tenant>|<target endpoint>|<field>} - binds a value to its row. */
    public static byte[] value(UUID tenantId, UUID targetEndpointId, String field) {
        return String.join("|", "tc:v1", Objects.requireNonNull(tenantId, "tenantId").toString(),
                Objects.requireNonNull(targetEndpointId, "targetEndpointId").toString(),
                Objects.requireNonNull(field, "field")).getBytes(StandardCharsets.UTF_8);
    }

    /** {@code dek:v1|<tenant>|<credential id>|<kek id>} - binds a wrapped data key to its row and key. */
    public static byte[] wrappedKey(UUID tenantId, UUID credentialId, String kekId) {
        return String.join("|", "dek:v1", Objects.requireNonNull(tenantId, "tenantId").toString(),
                Objects.requireNonNull(credentialId, "credentialId").toString(),
                Objects.requireNonNull(kekId, "kekId")).getBytes(StandardCharsets.UTF_8);
    }
}
