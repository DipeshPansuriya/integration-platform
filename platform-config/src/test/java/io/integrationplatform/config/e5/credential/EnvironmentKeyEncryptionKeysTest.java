package io.integrationplatform.config.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.credential.crypto.EnvironmentKeyEncryptionKeys;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

/**
 * ADR-007 Decision 7 rule 1 (T-103): the key-encryption keys come only from the deployment's secret store, read through
 * the environment-variable NAMES {@code PLATFORM_CREDENTIAL_KEK_<ID>} (32 bytes, base64) with the active id in
 * {@code PLATFORM_CREDENTIAL_KEK_ACTIVE} - never from a file, a table or a log. The test hands the names in through a
 * property source with keys generated in memory (R07).
 */
@ExtendWith(OutputCaptureExtension.class)
class EnvironmentKeyEncryptionKeysTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static String base64Key(int bytes) {
        byte[] key = new byte[bytes];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    @DisplayName("Every PLATFORM_CREDENTIAL_KEK_<ID> is a key by id; PLATFORM_CREDENTIAL_KEK_ACTIVE names the one that seals")
    void readsKeysByName() {
        String k1 = base64Key(32);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("PLATFORM_CREDENTIAL_KEK_K1", k1)
                .withProperty("PLATFORM_CREDENTIAL_KEK_K2", base64Key(32))
                .withProperty("PLATFORM_CREDENTIAL_KEK_ACTIVE", "K2");

        EnvironmentKeyEncryptionKeys keys = new EnvironmentKeyEncryptionKeys(environment);

        assertThat(keys.activeId()).contains("K2");
        SecretKey key1 = keys.find("K1").orElseThrow();
        assertThat(key1.getAlgorithm()).isEqualTo("AES");
        assertThat(key1.getEncoded()).isEqualTo(Base64.getDecoder().decode(k1));
        assertThat(keys.find("K2")).isPresent();
        assertThat(keys.find("K9")).as("an id this instance was deployed without").isEmpty();
        assertThat(keys.find("ACTIVE")).as("the active pointer is not a key").isEmpty();
        assertThat(keys.presentIds()).containsExactlyInAnyOrder("K1", "K2");
        assertThat(keys.toString()).doesNotContain(k1);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A key that is not 32 bytes of base64 is ignored and reported by its NAME only, never its value")
    void aMalformedKeyIsIgnoredAndNeverLogged(CapturedOutput output) {
        String shortKey = base64Key(16);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("PLATFORM_CREDENTIAL_KEK_K1", shortKey)
                .withProperty("PLATFORM_CREDENTIAL_KEK_K3", "not base64 !!")
                .withProperty("PLATFORM_CREDENTIAL_KEK_ACTIVE", "K1");

        EnvironmentKeyEncryptionKeys keys = new EnvironmentKeyEncryptionKeys(environment);

        assertThat(keys.find("K1")).isEmpty();
        assertThat(keys.find("K3")).isEmpty();
        assertThat(keys.activeId()).as("the active id names no usable key").isEmpty();
        assertThat(output).contains("PLATFORM_CREDENTIAL_KEK_K1").doesNotContain(shortKey)
                .doesNotContain("not base64 !!");
    }

    @Test
    @DisplayName("No key configured: nothing to seal with, nothing found (the save answers 503; readiness decides the rest)")
    void noKeys() {
        EnvironmentKeyEncryptionKeys keys = new EnvironmentKeyEncryptionKeys(new MockEnvironment());
        assertThat(keys.activeId()).isEmpty();
        assertThat(keys.presentIds()).isEmpty();
    }
}
