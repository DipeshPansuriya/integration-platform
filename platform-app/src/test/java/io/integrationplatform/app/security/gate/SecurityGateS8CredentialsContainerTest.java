package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import io.integrationplatform.config.credential.api.CredentialUnreadableException;
import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.api.KeyUnavailableException;
import io.integrationplatform.config.credential.api.SealedCredential;
import io.integrationplatform.config.credential.crypto.EnvelopeSealer;
import io.integrationplatform.config.credential.readiness.TargetCredentialKeysHealthIndicator;
import io.integrationplatform.routing.credential.EnvelopeOpener;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T141 / T-147 Release 1 security gate, suite S8 (Credentials), from release-1-security-test-plan.md.
 * Every case is disabled until the tracker item it names is on main; the area roll-up is
 * {@link Release1Gate} and the case list is {@link Release1GateCatalogue}. Container tag: never runs by default.
 */
@Tag("container")
class SecurityGateS8CredentialsContainerTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static SecretKey newKek() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new SecretKeySpec(bytes, "AES");
    }

    private static KeyEncryptionKeys keyring(Map<String, SecretKey> held) {
        return new KeyEncryptionKeys() {
            @Override
            public Optional<SecretKey> find(String kekId) {
                return Optional.ofNullable(held.get(kekId));
            }

            @Override
            public Optional<String> activeId() {
                return held.keySet().stream().findFirst();
            }
        };
    }

    private static SealedCredential seal(String value, UUID tenant, UUID credential, UUID endpoint, String field,
            String kekId, SecretKey kek) {
        EnvelopeSealer.Sealed sealed = EnvelopeSealer.seal(value.toCharArray(), tenant, credential, endpoint, field,
                kekId, kek);
        return new SealedCredential(credential, tenant, endpoint, field, kekId, sealed.wrappedKey(),
                sealed.ciphertext());
    }

    /**
     * S8-A1: Associated-data binding (rule 3).
     * Expected: Decrypt fails (auth-tag mismatch, associated data tc:v1
     */
    @Test
    void s8_a1_associated_data_binding() {
        SecretKey kek = newKek();
        UUID tenant = UUID.randomUUID();
        UUID credential = UUID.randomUUID();
        UUID endpoint = UUID.randomUUID();
        String plaintext = "s3cret-" + UUID.randomUUID();
        SealedCredential row = seal(plaintext, tenant, credential, endpoint, "BASIC_PASSWORD", "k1", kek);
        KeyEncryptionKeys keys = keyring(Map.of("k1", kek, "k2", kek));
        assertThat(new String(EnvelopeOpener.open(row, keys))).as("the untouched row opens").isEqualTo(plaintext);

        SealedCredential otherCredential = seal("other-" + UUID.randomUUID(), tenant, UUID.randomUUID(), endpoint,
                "BASIC_PASSWORD", "k1", kek);
        byte[] flipped = row.ciphertext().clone();
        flipped[flipped.length - 1] ^= 0x01;
        Map<String, SealedCredential> tampered = Map.of(
                "row copied to another tenant", new SealedCredential(credential, UUID.randomUUID(), endpoint,
                        row.field(), row.kekId(), row.wrappedKey(), row.ciphertext()),
                "row copied to another endpoint", new SealedCredential(credential, tenant, UUID.randomUUID(),
                        row.field(), row.kekId(), row.wrappedKey(), row.ciphertext()),
                "row relabelled to another field", new SealedCredential(credential, tenant, endpoint,
                        "BEARER_TOKEN", row.kekId(), row.wrappedKey(), row.ciphertext()),
                "row relabelled to another credential id", new SealedCredential(UUID.randomUUID(), tenant, endpoint,
                        row.field(), row.kekId(), row.wrappedKey(), row.ciphertext()),
                "key id re-labelled (same key held under k2)", new SealedCredential(credential, tenant, endpoint,
                        row.field(), "k2", row.wrappedKey(), row.ciphertext()),
                "value blob swapped from another credential", new SealedCredential(credential, tenant, endpoint,
                        row.field(), row.kekId(), row.wrappedKey(), otherCredential.ciphertext()),
                "one ciphertext bit flipped", new SealedCredential(credential, tenant, endpoint, row.field(),
                        row.kekId(), row.wrappedKey(), flipped));
        tampered.forEach((what, bad) -> assertThatThrownBy(() -> EnvelopeOpener.open(bad, keys)).as(what)
                .isInstanceOf(CredentialUnreadableException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage()) + e.getCause()).as(what)
                        .doesNotContain(plaintext)));
    }

    /**
     * S8-A2: Write-only secret API (rule 8).
     * Expected: Response shows masked/absent secret values only - never the value, ciphertext or wrapped key, in API responses, exports or audit rows
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: dependency T142 is on main but the case needs the admin API over HTTP with a real tenant B (platform-e2e TenantBSeed/AdminApi, unreachable from platform-app) - move to platform-e2e or write against a SpringBootTest; body not yet written (T141 gate case S8-A2)")
    void s8_a2_write_only_secret_api() {
        fail("S8-A2 body lands with T142");
    }

    /**
     * S8-A3: Snapshot holds ids only (rule 9).
     * Expected: Only credential **ids**; 0 occurrences of target_credential value/ciphertext/wrapped-key fields (pinned test target)
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: dependency T142 is on main but the case needs the admin API over HTTP with a real tenant B (platform-e2e TenantBSeed/AdminApi, unreachable from platform-app) - move to platform-e2e or write against a SpringBootTest; body not yet written (T141 gate case S8-A3)")
    void s8_a3_snapshot_holds_ids_only() {
        fail("S8-A3 body lands with T142");
    }

    /**
     * S8-A4: KEK handling (rule 1).
     * Expected: KEK read from the deployment secret store by env name; never in a file, table or log; value never printed by this review either (credential-storage rule)
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: dependency T142 is on main but the case needs the admin API over HTTP with a real tenant B (platform-e2e TenantBSeed/AdminApi, unreachable from platform-app) - move to platform-e2e or write against a SpringBootTest; body not yet written (T141 gate case S8-A4)")
    void s8_a4_kek_handling() {
        fail("S8-A4 body lands with T142");
    }

    /**
     * S8-A5: Unknown-KEK-id readiness fail (rule 5, error path S-d).
     * Expected: Unknown id: no call made, no attempt row written, KEY_UNAVAILABLE event, alert, instance **fails readiness**, consumers stop (ADR-010 §9). Distinguish from auth-tag mismatch (permanent dead-letter) - run both branches
     */
    @Test
    void s8_a5_unknown_kek_id_readiness_fail() {
        SecretKey kek = newKek();
        SealedCredential row = seal("v-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "BASIC_PASSWORD", "k-gone", kek);
        KeyEncryptionKeys withoutTheKey = keyring(Map.of("k-other", newKek()));

        // branch 1: an id this instance does not hold is a KEY fault, before anything is decrypted ...
        assertThatThrownBy(() -> EnvelopeOpener.open(row, withoutTheKey))
                .isInstanceOf(KeyUnavailableException.class)
                .satisfies(e -> assertThat(((KeyUnavailableException) e).kekId()).isEqualTo("k-gone"));
        // ... distinct from branch 2: a held key with an auth-tag mismatch is the permanent, unreadable fault
        SealedCredential relabelled = new SealedCredential(row.id(), row.tenantId(), row.targetEndpointId(),
                row.field(), "k-held", row.wrappedKey(), row.ciphertext());
        assertThatThrownBy(() -> EnvelopeOpener.open(relabelled, keyring(Map.of("k-held", newKek()))))
                .isInstanceOf(CredentialUnreadableException.class);

        // the instance fails readiness (503 on the readiness group) while a referenced key id is missing
        var notReady = new TargetCredentialKeysHealthIndicator(withoutTheKey, () -> Set.of("k-gone"));
        assertThat(notReady.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
        assertThat(notReady.health().getDetails().toString()).contains("k-gone").doesNotContain("k-other");
        var ready = new TargetCredentialKeysHealthIndicator(keyring(Map.of("k-gone", kek)), () -> Set.of("k-gone"));
        assertThat(ready.health().getStatus().getCode()).isEqualTo("UP");
        // a delivery that found the key missing also pulls the instance out, even when no row references it yet
        var reported = new TargetCredentialKeysHealthIndicator(withoutTheKey, () -> Set.of());
        assertThat(reported.health().getStatus().getCode()).isEqualTo("UP");
        reported.reportMissing("k-gone");
        assertThat(reported.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
    }

    /**
     * S8-A6: Data-key hygiene (rule 2).
     * Expected: Each update mints a **new data key** and nonce (a key encrypts exactly one value); no nonce reuse at any time (inspect two ciphertexts)
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: dependency T142 is on main but the case needs the admin API over HTTP with a real tenant B (platform-e2e TenantBSeed/AdminApi, unreachable from platform-app) - move to platform-e2e or write against a SpringBootTest; body not yet written (T141 gate case S8-A6)")
    void s8_a6_data_key_hygiene() {
        fail("S8-A6 body lands with T142");
    }
}
