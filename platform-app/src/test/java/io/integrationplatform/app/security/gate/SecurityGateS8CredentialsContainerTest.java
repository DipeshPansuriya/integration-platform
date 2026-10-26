package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T141 / T-147 Release 1 security gate, suite S8 (Credentials), from release-1-security-test-plan.md.
 * Every case is disabled until the tracker item it names is on main; the area roll-up is
 * {@link Release1Gate} and the case list is {@link Release1GateCatalogue}. Container tag: never runs by default.
 */
@Tag("container")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
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
     * S8-A4: KEK handling (rule 1).
     * Expected: KEK read from the deployment secret store by env name; never in a file, table or log; value never printed by this review either (credential-storage rule)
     */
    @Test
    void s8_a4_kek_handling(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // keys come from environment variables by NAME and live in memory only
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String kekBase64 = java.util.Base64.getEncoder().encodeToString(raw);
        String malformed = "not-a-kek-" + UUID.randomUUID();
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("PLATFORM_CREDENTIAL_KEK_K1", kekBase64)
                .withProperty("PLATFORM_CREDENTIAL_KEK_BAD", malformed)
                .withProperty("PLATFORM_CREDENTIAL_KEK_ACTIVE", "K1");
        var keys = new io.integrationplatform.config.credential.crypto.EnvironmentKeyEncryptionKeys(env);
        assertThat(keys.find("K1")).as("the key is read by env name").isPresent();
        assertThat(keys.activeId()).contains("K1");
        assertThat(keys.find("BAD")).as("a malformed value is ignored").isEmpty();
        // the value is never printed: not by toString, not by the log (a malformed one is reported by NAME only)
        assertThat(keys.toString()).doesNotContain(kekBase64).doesNotContain(malformed);
        assertThat(output.getAll()).contains("PLATFORM_CREDENTIAL_KEK_BAD").doesNotContain(kekBase64)
                .doesNotContain(malformed);
        SealedCredential row = seal("v-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "BASIC_PASSWORD", "K1", keys.find("K1").orElseThrow());
        assertThat(java.util.Base64.getEncoder().encodeToString(row.wrappedKey())).doesNotContain(kekBase64);
        assertThat(java.util.Base64.getEncoder().encodeToString(row.ciphertext())).doesNotContain(kekBase64);
        // never in a file: no text file under the code root holds a literal KEK value for the variable
        java.nio.file.Path root = java.nio.file.Files.exists(java.nio.file.Path.of("compose"))
                ? java.nio.file.Path.of(".") : java.nio.file.Path.of("..");
        java.util.regex.Pattern literal = java.util.regex.Pattern.compile(
                "PLATFORM_CREDENTIAL_KEK_[A-Z0-9]+\\s*[:=]\\s*[\"']?(?!\\$\\{)[A-Za-z0-9+/]{43}=");
        java.util.List<String> hits = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.walk(root)) {
            files.filter(java.nio.file.Files::isRegularFile).filter(f -> {
                String n = f.toString().replace('\\', '/');
                return !n.contains("/target/") && !n.contains("/node_modules/") && !n.contains("/.git/")
                        && n.matches(".*\\.(java|yaml|yml|properties|sql|json|env|sh|md)$");
            }).forEach(f -> {
                try {
                    if (literal.matcher(java.nio.file.Files.readString(f)).find()) {
                        hits.add(f.toString());
                    }
                } catch (java.io.IOException | RuntimeException unreadable) {
                    // binary or unreadable: not a text secret
                }
            });
        }
        assertThat(hits).as("files holding a literal KEK value").isEmpty();
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
    void s8_a6_data_key_hygiene() {
        SecretKey kek = newKek();
        UUID tenant = UUID.randomUUID();
        UUID credential = UUID.randomUUID();
        UUID endpoint = UUID.randomUUID();
        String value = "same-value-" + UUID.randomUUID();
        java.util.HexFormat hex = java.util.HexFormat.of();
        java.util.Set<String> wrapped = new java.util.HashSet<>();
        java.util.Set<String> valueNonces = new java.util.HashSet<>();
        java.util.Set<String> wrapNonces = new java.util.HashSet<>();
        java.util.Set<String> ciphertexts = new java.util.HashSet<>();
        int rounds = 200;
        for (int i = 0; i < rounds; i++) { // sequential updates of one value: nothing repeats
            EnvelopeSealer.Sealed sealed = EnvelopeSealer.seal(value.toCharArray(), tenant, credential, endpoint,
                    "BASIC_PASSWORD", "k1", kek);
            wrapped.add(hex.formatHex(sealed.wrappedKey()));
            ciphertexts.add(hex.formatHex(sealed.ciphertext()));
            wrapNonces.add(hex.formatHex(sealed.wrappedKey(), 0, 12));
            valueNonces.add(hex.formatHex(sealed.ciphertext(), 0, 12));
            assertThat(new String(sealed.ciphertext(), java.nio.charset.StandardCharsets.ISO_8859_1))
                    .doesNotContain(value);
        }
        assertThat(wrapped).as("a fresh wrapped data key every update").hasSize(rounds);
        assertThat(ciphertexts).as("two ciphertexts of one value differ").hasSize(rounds);
        assertThat(valueNonces).as("no value nonce reused").hasSize(rounds);
        assertThat(wrapNonces).as("no wrap nonce reused").hasSize(rounds);
        SealedCredential first = seal(value, tenant, credential, endpoint, "BASIC_PASSWORD", "k1", kek);
        SealedCredential second = seal(value, tenant, credential, endpoint, "BASIC_PASSWORD", "k1", kek);
        assertThat(first.wrappedKey()).isNotEqualTo(second.wrappedKey());
        KeyEncryptionKeys keys = keyring(Map.of("k1", kek));
        assertThat(new String(EnvelopeOpener.open(first, keys))).isEqualTo(value);
        assertThat(new String(EnvelopeOpener.open(second, keys))).isEqualTo(value);
    }
}
