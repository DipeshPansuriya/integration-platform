package io.integrationplatform.routing.credential;

import io.integrationplatform.config.credential.api.CredentialAad;
import io.integrationplatform.config.credential.api.CredentialUnreadableException;
import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.api.KeyUnavailableException;
import io.integrationplatform.config.credential.api.SealedCredential;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The opening half of ADR-007 Decision 7 (T-185; rules 3, 5, 6): the ONLY place a target credential is decrypted, in
 * platform-routing at send time. The key-encryption key is looked up by the row's kek id first - an id this instance
 * does not hold is {@link KeyUnavailableException} before anything is decrypted; then the wrapped data key is opened
 * with the KEK and its associated data, and the value with the data key and its own associated data - any tag mismatch
 * (tampering, a row copied from another tenant, endpoint or field, a re-labelled key id) is
 * {@link CredentialUnreadableException}. The data key is wiped as soon as the value is out; neither exception carries
 * ciphertext, plaintext or the JDK's crypto message.
 */
public final class EnvelopeOpener {

    private EnvelopeOpener() {
    }

    /** The plaintext of one sealed field; the caller holds it for the call only and wipes it. */
    public static char[] open(SealedCredential sealed, KeyEncryptionKeys keys) {
        SecretKey kek = keys.find(sealed.kekId()).orElseThrow(() -> new KeyUnavailableException(sealed.kekId()));
        byte[] dataKey = decrypt(kek, sealed.wrappedKey(),
                CredentialAad.wrappedKey(sealed.tenantId(), sealed.id(), sealed.kekId()), sealed);
        byte[] plaintext = null;
        try {
            plaintext = decrypt(new SecretKeySpec(dataKey, "AES"), sealed.ciphertext(),
                    CredentialAad.value(sealed.tenantId(), sealed.targetEndpointId(), sealed.field()), sealed);
            return utf8(plaintext, sealed);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private static byte[] decrypt(SecretKey key, byte[] blob, byte[] associatedData, SealedCredential sealed) {
        if (blob.length < CredentialAad.NONCE_BYTES + CredentialAad.TAG_BITS / 8) {
            throw new CredentialUnreadableException(sealed.id());
        }
        try {
            Cipher cipher = Cipher.getInstance(CredentialAad.TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(CredentialAad.TAG_BITS, blob, 0, CredentialAad.NONCE_BYTES));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(blob, CredentialAad.NONCE_BYTES, blob.length - CredentialAad.NONCE_BYTES);
        } catch (AEADBadTagException tagMismatch) {
            throw new CredentialUnreadableException(sealed.id());
        } catch (GeneralSecurityException | IllegalArgumentException malformed) {
            // a wrong-length key or a malformed blob is unreadable too - never retried, never echoed
            throw new CredentialUnreadableException(sealed.id());
        }
    }

    private static char[] utf8(byte[] plaintext, SealedCredential sealed) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext));
            char[] value = Arrays.copyOfRange(decoded.array(), decoded.position(), decoded.limit());
            Arrays.fill(decoded.array(), '\0');
            return value;
        } catch (CharacterCodingException notText) {
            throw new CredentialUnreadableException(sealed.id());
        }
    }
}
