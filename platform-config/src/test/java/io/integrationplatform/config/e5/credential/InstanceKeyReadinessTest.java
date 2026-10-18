package io.integrationplatform.config.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.readiness.InstanceKeyReadiness;
import io.integrationplatform.config.credential.readiness.TargetCredentialKeysHealthIndicator;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-201 (E10-F15): the instance readiness the stage consumers and the retry pass ask is the key health check - UP
 * means ready; anything else (a referenced key absent, or a send that found one missing) means not ready.
 */
class InstanceKeyReadinessTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static KeyEncryptionKeys holding(String... ids) {
        Map<String, SecretKey> keys = new HashMap<>();
        for (String id : ids) {
            byte[] material = new byte[32];
            RANDOM.nextBytes(material);
            keys.put(id, new SecretKeySpec(material, "AES"));
        }
        return new KeyEncryptionKeys() {
            @Override
            public Optional<SecretKey> find(String kekId) {
                return Optional.ofNullable(keys.get(kekId));
            }

            @Override
            public Optional<String> activeId() {
                return ids.length == 0 ? Optional.empty() : Optional.of(ids[0]);
            }
        };
    }

    @Test
    @DisplayName("Key health UP: the instance is ready")
    void upIsReady() {
        TargetCredentialKeysHealthIndicator indicator = new TargetCredentialKeysHealthIndicator(holding("K1"),
                () -> Set.of("K1"));
        assertThat(new InstanceKeyReadiness(indicator).getAsBoolean()).isTrue();
    }

    @Test
    @Tag("negative")
    @DisplayName("A referenced key absent at start: health DOWN, the instance is not ready")
    void absentReferencedKeyIsNotReady() {
        TargetCredentialKeysHealthIndicator indicator = new TargetCredentialKeysHealthIndicator(holding("K1"),
                () -> Set.of("K1", "K2"));
        assertThat(new InstanceKeyReadiness(indicator).getAsBoolean()).isFalse();
    }

    @Test
    @Tag("negative")
    @DisplayName("A send found a key missing: the ready instance turns not ready")
    void reportedMissingKeyTurnsNotReady() {
        TargetCredentialKeysHealthIndicator indicator = new TargetCredentialKeysHealthIndicator(holding("K1"),
                () -> Set.of("K1"));
        InstanceKeyReadiness readiness = new InstanceKeyReadiness(indicator);
        assertThat(readiness.getAsBoolean()).isTrue();

        indicator.reportMissing("K9");

        assertThat(readiness.getAsBoolean()).isFalse();
    }
}
