package io.integrationplatform.config.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.readiness.CredentialKeyReferences;
import io.integrationplatform.config.credential.readiness.TargetCredentialKeysHealthIndicator;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * ADR-007 Decision 7 rule 5 conformance (T-185, S-d; ADR-010 section 9 item 4): an instance that lacks a key id a
 * credential row references is pulled out of service - its readiness check reports OUT_OF_SERVICE (the readiness group
 * answers 503) - both at start-up (every referenced key id must be present) and once a send found a key missing. Only
 * key ids appear in the details; never key material.
 */
class TargetCredentialKeysHealthIndicatorTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static KeyEncryptionKeys holding(String... ids) {
        Map<String, SecretKey> keys = new java.util.HashMap<>();
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
    @DisplayName("Every referenced key id present: UP")
    void allPresentIsUp() {
        TargetCredentialKeysHealthIndicator indicator = new TargetCredentialKeysHealthIndicator(holding("K1", "K2"),
                () -> Set.of("K1", "K2"));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @Tag("negative")
    @DisplayName("Start-up check: a key id referenced by a credential row is missing - not ready, the id named")
    void aMissingReferencedKeyIsNotReady() {
        TargetCredentialKeysHealthIndicator indicator = new TargetCredentialKeysHealthIndicator(holding("K2"),
                () -> Set.of("K1", "K2"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("missingKeyIds", java.util.List.of("K1"));
    }

    @Test
    @Tag("negative")
    @DisplayName("Send time: a delivery found a key missing - the instance stops being ready")
    void aKeyMissingAtSendTimeIsNotReady() {
        TargetCredentialKeysHealthIndicator indicator = new TargetCredentialKeysHealthIndicator(holding("K2"),
                Set::of);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        indicator.reportMissing("K7");

        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(indicator.health().getDetails()).containsEntry("missingKeyIds", java.util.List.of("K7"));
    }

    @Test
    @Tag("negative")
    @DisplayName("The start-up check cannot read the referenced ids: not ready (fail closed), asked again on the next probe")
    void anUnreadableReferenceListIsNotReady() {
        AtomicInteger asked = new AtomicInteger();
        CredentialKeyReferences failing = () -> {
            asked.incrementAndGet();
            throw new IllegalStateException("database unavailable");
        };
        TargetCredentialKeysHealthIndicator indicator = new TargetCredentialKeysHealthIndicator(holding("K1"),
                failing);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(asked).hasValue(2);
    }
}
