package io.integrationplatform.config.credential.crypto;

import io.integrationplatform.config.credential.api.CredentialAad;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The sealing half of ADR-007 Decision 7 (T-103): envelope encryption in the JVM with the JDK only. Every call draws a
 * fresh random 256-bit data key and two random 96-bit nonces, encrypts the value with the data key (AES-256-GCM, the
 * value's associated data), wraps the data key with the key-encryption key (AES-256-GCM, the wrapped key's associated
 * data), and wipes the plaintext bytes and the data key from memory. A data key encrypts exactly one value (rule 2).
 * Layout of each blob: {@link CredentialAad}. The opening half lives only in platform-routing (rule 6).
 */
public final class EnvelopeSealer {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The two blobs of a sealed value; ciphertext only. */
    public record Sealed(byte[] wrappedKey, byte[] ciphertext) {

        public Sealed {
            Objects.requireNonNull(wrappedKey, "wrappedKey");
            Objects.requireNonNull(ciphertext, "ciphertext");
        }

        @Override
        public String toString() {
            return "Sealed[" + wrappedKey.length + "+" + ciphertext.length + " bytes]";
        }
    }

    private EnvelopeSealer() {
    }

    /**
     * Seals one credential value.
     *
     * @param value the plaintext; read, never kept (the caller wipes its own array)
     * @param field the register name of the field ({@code BASIC_PASSWORD}, ...)
     * @param kek   the key-encryption key named {@code kekId} (256 bits)
     */
    public static Sealed seal(char[] value, UUID tenantId, UUID credentialId, UUID targetEndpointId, String field,
            String kekId, SecretKey kek) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(kek, "kek");
        byte[] kekBytes = kek.getEncoded();
        if (kekBytes == null || kekBytes.length != CredentialAad.KEY_BYTES) {
            throw new IllegalArgumentException("A key-encryption key must be 256 bits (32 bytes)");
        }
        Arrays.fill(kekBytes, (byte) 0);
        byte[] dataKey = new byte[CredentialAad.KEY_BYTES];
        byte[] plaintext = utf8(value);
        try {
            RANDOM.nextBytes(dataKey);
            byte[] ciphertext = encrypt(new SecretKeySpec(dataKey, "AES"), plaintext,
                    CredentialAad.value(tenantId, targetEndpointId, field));
            byte[] wrappedKey = encrypt(kek, dataKey, CredentialAad.wrappedKey(tenantId, credentialId, kekId));
            return new Sealed(wrappedKey, ciphertext);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static byte[] encrypt(SecretKey key, byte[] plaintext, byte[] associatedData) {
        byte[] nonce = new byte[CredentialAad.NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(CredentialAad.TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(CredentialAad.TAG_BITS, nonce));
            cipher.updateAAD(associatedData);
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException failure) {
            // the JDK's AES-GCM is always present; the message carries no key material or value
            throw new IllegalStateException("AES-GCM sealing failed: " + failure.getClass().getSimpleName());
        }
    }

    private static byte[] utf8(char[] value) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] bytes = Arrays.copyOfRange(encoded.array(), encoded.position(), encoded.limit());
        Arrays.fill(encoded.array(), (byte) 0);
        return bytes;
    }
}
