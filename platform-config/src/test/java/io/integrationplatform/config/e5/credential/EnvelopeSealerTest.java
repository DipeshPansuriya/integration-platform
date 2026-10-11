package io.integrationplatform.config.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.credential.api.CredentialAad;
import io.integrationplatform.config.credential.crypto.EnvelopeSealer;
import io.integrationplatform.config.credential.crypto.EnvelopeSealer.Sealed;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-007 Decision 7 conformance, the sealing half (T-103; rules 1, 2 and 3): every credential value gets its own
 * random 256-bit data key and is encrypted with AES-256-GCM under a random 96-bit nonce; the data key is wrapped with
 * the key-encryption key, also AES-256-GCM; the associated data binds the value to {@code tc:v1|tenant|endpoint|field}
 * and the wrapped key to {@code dek:v1|tenant|credential id|kek id}. The stored layout is standard JDK AES-GCM -
 * nonce (12 bytes) followed by ciphertext and the 128-bit tag - so the opening half (platform-routing, send time) and
 * this test open it with nothing but {@code javax.crypto}.
 */
class EnvelopeSealerTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey kek = key(32);
    private final UUID tenant = UUID.randomUUID();
    private final UUID credential = UUID.randomUUID();
    private final UUID endpoint = UUID.randomUUID();

    private static SecretKey key(int bytes) {
        byte[] material = new byte[bytes];
        RANDOM.nextBytes(material);
        return new SecretKeySpec(material, "AES");
    }

    @Test
    @DisplayName("Rule 3: the associated data strings are exactly tc:v1|tenant|endpoint|field and dek:v1|tenant|credential|kek")
    void associatedData() {
        assertThat(new String(CredentialAad.value(tenant, endpoint, "BASIC_PASSWORD"), StandardCharsets.UTF_8))
                .isEqualTo("tc:v1|" + tenant + "|" + endpoint + "|BASIC_PASSWORD");
        assertThat(new String(CredentialAad.wrappedKey(tenant, credential, "K1"), StandardCharsets.UTF_8))
                .isEqualTo("dek:v1|" + tenant + "|" + credential + "|K1");
    }

    @Test
    @DisplayName("Rules 1-2: the layout is nonce(12) + AES-GCM(ciphertext + 128-bit tag); the wrapped key carries a 32-byte key")
    void layout() {
        Sealed sealed = EnvelopeSealer.seal("p4ssw0rd".toCharArray(), tenant, credential, endpoint,
                "BASIC_PASSWORD", "K1", kek);

        assertThat(sealed.wrappedKey()).hasSize(12 + 32 + 16);
        assertThat(sealed.ciphertext()).hasSize(12 + "p4ssw0rd".length() + 16);
        assertThat(containsSlice(sealed.ciphertext(), "p4ssw0rd".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    @DisplayName("Standard AES-GCM: the wrapped key opens with the KEK and its AAD, the value with that data key and its AAD")
    void opensWithPlainJdkAesGcm() throws Exception {
        Sealed sealed = EnvelopeSealer.seal("open-me".toCharArray(), tenant, credential, endpoint, "API_KEY", "K1",
                kek);

        byte[] dataKey = open(kek, sealed.wrappedKey(), CredentialAad.wrappedKey(tenant, credential, "K1"));
        assertThat(dataKey).hasSize(32);
        byte[] value = open(new SecretKeySpec(dataKey, "AES"), sealed.ciphertext(),
                CredentialAad.value(tenant, endpoint, "API_KEY"));
        assertThat(new String(value, StandardCharsets.UTF_8)).isEqualTo("open-me");
    }

    @Test
    @DisplayName("Rule 2: every seal draws a new data key and new nonces - the same value never seals the same way twice")
    void freshDataKeyAndNoncesEveryTime() throws Exception {
        Sealed first = EnvelopeSealer.seal("same".toCharArray(), tenant, credential, endpoint, "API_KEY", "K1", kek);
        Sealed second = EnvelopeSealer.seal("same".toCharArray(), tenant, credential, endpoint, "API_KEY", "K1", kek);

        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.wrappedKey()).isNotEqualTo(second.wrappedKey());
        assertThat(Arrays.copyOf(first.ciphertext(), 12)).isNotEqualTo(Arrays.copyOf(second.ciphertext(), 12));
        assertThat(Arrays.copyOf(first.wrappedKey(), 12)).isNotEqualTo(Arrays.copyOf(first.ciphertext(), 12));
        byte[] key1 = open(kek, first.wrappedKey(), CredentialAad.wrappedKey(tenant, credential, "K1"));
        byte[] key2 = open(kek, second.wrappedKey(), CredentialAad.wrappedKey(tenant, credential, "K1"));
        assertThat(key1).as("one data key encrypts exactly one value").isNotEqualTo(key2);
    }

    @Test
    @Tag("negative")
    @DisplayName("Rule 1: a key-encryption key that is not 256 bits is refused; the message never carries key material")
    void refusesAShortKek() {
        SecretKey shortKey = key(16);
        assertThatThrownBy(() -> EnvelopeSealer.seal("x".toCharArray(), tenant, credential, endpoint, "API_KEY", "K1",
                shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256");
    }

    private static byte[] open(SecretKey key, byte[] sealed, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
        cipher.updateAAD(aad);
        return cipher.doFinal(sealed, 12, sealed.length - 12);
    }

    private static boolean containsSlice(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
