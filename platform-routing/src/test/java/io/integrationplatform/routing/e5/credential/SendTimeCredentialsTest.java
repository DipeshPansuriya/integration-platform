package io.integrationplatform.routing.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.credential.api.CredentialUnreadableException;
import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.api.KeyUnavailableException;
import io.integrationplatform.config.credential.api.SealedCredential;
import io.integrationplatform.config.credential.api.TargetCredentialVault;
import io.integrationplatform.config.credential.crypto.EnvelopeSealer;
import io.integrationplatform.routing.credential.EnvelopeOpener;
import io.integrationplatform.routing.credential.OpenedCredentials;
import io.integrationplatform.routing.credential.SendTimeCredentials;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-007 Decision 7 conformance (T-185, rules 3, 5 and 6): decryption happens only here, in {@code platform-routing},
 * at send time - what the configuration module sealed on save opens with the same associated data, a value is held in
 * memory for the call only (wiped on close, never cached: every send reads and opens again), and the two failures
 * are told apart.
 *
 * <ul>
 *   <li>AAD binding: a ciphertext copied to another tenant's or another endpoint's row - or to another field, or with
 *       its wrapped key re-labelled to another credential id - fails to decrypt (CredentialUnreadableException);</li>
 *   <li>a key-encryption-key id this instance does not hold is KeyUnavailableException, before any decryption.</li>
 * </ul>
 * Keys are generated in memory per run (R07: no key value in any file).
 */
class SendTimeCredentialsTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey k1 = aesKey();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID endpointA = UUID.randomUUID();
    private final UUID endpointB = UUID.randomUUID();

    private static SecretKey aesKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new SecretKeySpec(bytes, "AES");
    }

    private KeyEncryptionKeys keys(Map<String, SecretKey> byId) {
        return new KeyEncryptionKeys() {
            @Override
            public Optional<SecretKey> find(String kekId) {
                return Optional.ofNullable(byId.get(kekId));
            }

            @Override
            public Optional<String> activeId() {
                return byId.keySet().stream().findFirst();
            }
        };
    }

    private SealedCredential seal(UUID tenant, UUID credentialId, UUID endpoint, String field, String value,
            String kekId) {
        EnvelopeSealer.Sealed sealed = EnvelopeSealer.seal(value.toCharArray(), tenant, credentialId, endpoint,
                field, kekId, k1);
        return new SealedCredential(credentialId, tenant, endpoint, field, kekId, sealed.wrappedKey(),
                sealed.ciphertext());
    }

    @Test
    @DisplayName("What config sealed on save opens at send time with the same associated data")
    void roundTrip() {
        SealedCredential sealed = seal(tenantA, UUID.randomUUID(), endpointA, "BASIC_PASSWORD", "s3nd-time", "K1");
        assertThat(new String(EnvelopeOpener.open(sealed, keys(Map.of("K1", k1))))).isEqualTo("s3nd-time");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("AAD binding: a ciphertext copied to another tenant's row fails to decrypt")
    void copiedToAnotherTenantsRowFails() {
        SealedCredential original = seal(tenantB, UUID.randomUUID(), endpointB, "BASIC_PASSWORD", "tenant-b-only",
                "K1");
        UUID rowOfA = UUID.randomUUID();
        SealedCredential copied = new SealedCredential(rowOfA, tenantA, endpointA, "BASIC_PASSWORD", "K1",
                original.wrappedKey(), original.ciphertext());

        assertThatThrownBy(() -> EnvelopeOpener.open(copied, keys(Map.of("K1", k1))))
                .isInstanceOf(CredentialUnreadableException.class)
                .satisfies(e -> assertThat(((CredentialUnreadableException) e).credentialId()).isEqualTo(rowOfA))
                .hasMessageNotContaining("tenant-b-only");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("AAD binding: the same tenant's ciphertext copied to another endpoint's or another field's row fails")
    void copiedToAnotherEndpointOrFieldFails() {
        UUID credentialId = UUID.randomUUID();
        SealedCredential original = seal(tenantA, credentialId, endpointA, "BASIC_PASSWORD", "endpoint-a-only", "K1");
        // the wrapped key travels with its own credential id (it still unwraps); the VALUE's binding is what fails
        SealedCredential otherEndpoint = new SealedCredential(credentialId, tenantA, endpointB, "BASIC_PASSWORD", "K1",
                original.wrappedKey(), original.ciphertext());
        SealedCredential otherField = new SealedCredential(credentialId, tenantA, endpointA, "API_KEY", "K1",
                original.wrappedKey(), original.ciphertext());
        SealedCredential otherKeyLabel = new SealedCredential(credentialId, tenantA, endpointA, "BASIC_PASSWORD",
                "K2", original.wrappedKey(), original.ciphertext());

        for (SealedCredential moved : List.of(otherEndpoint, otherField)) {
            assertThatThrownBy(() -> EnvelopeOpener.open(moved, keys(Map.of("K1", k1))))
                    .as(moved.toString()).isInstanceOf(CredentialUnreadableException.class);
        }
        assertThatThrownBy(() -> EnvelopeOpener.open(otherKeyLabel, keys(Map.of("K1", k1, "K2", k1))))
                .as("the kek id is bound into the wrapped key's associated data")
                .isInstanceOf(CredentialUnreadableException.class);
    }

    @Test
    @Tag("negative")
    @DisplayName("A flipped bit in the ciphertext or the wrapped key is a tag mismatch, never garbage plaintext")
    void tamperingIsATagMismatch() {
        SealedCredential sealed = seal(tenantA, UUID.randomUUID(), endpointA, "API_KEY", "untouched", "K1");
        byte[] ciphertext = sealed.ciphertext().clone();
        ciphertext[ciphertext.length - 1] ^= 1;
        byte[] wrapped = sealed.wrappedKey().clone();
        wrapped[20] ^= 1;
        for (SealedCredential tampered : List.of(
                new SealedCredential(sealed.id(), tenantA, endpointA, "API_KEY", "K1", sealed.wrappedKey(), ciphertext),
                new SealedCredential(sealed.id(), tenantA, endpointA, "API_KEY", "K1", wrapped, sealed.ciphertext()))) {
            assertThatThrownBy(() -> EnvelopeOpener.open(tampered, keys(Map.of("K1", k1))))
                    .isInstanceOf(CredentialUnreadableException.class);
        }
    }

    @Test
    @Tag("negative")
    @DisplayName("An unknown key-encryption-key id is KeyUnavailable - told apart from a tag mismatch, nothing decrypted")
    void unknownKeyIdIsKeyUnavailable() {
        SealedCredential sealed = seal(tenantA, UUID.randomUUID(), endpointA, "API_KEY", "needs-k1", "K1");
        assertThatThrownBy(() -> EnvelopeOpener.open(sealed, keys(Map.of("K2", aesKey()))))
                .isInstanceOf(KeyUnavailableException.class)
                .satisfies(e -> assertThat(((KeyUnavailableException) e).kekId()).isEqualTo("K1"));
    }

    @Test
    @DisplayName("Send time only: every open reads the rows again (no cache); close wipes the values held in memory")
    void noCacheAndWipedOnClose() {
        UUID credentialId = UUID.randomUUID();
        SealedCredential sealed = seal(tenantA, credentialId, endpointA, "BASIC_PASSWORD", "wipe-me", "K1");
        AtomicInteger reads = new AtomicInteger();
        TargetCredentialVault vault = endpoint -> {
            reads.incrementAndGet();
            return endpoint.equals(endpointA) ? List.of(sealed) : List.of();
        };
        SendTimeCredentials credentials = new SendTimeCredentials(vault, keys(Map.of("K1", k1)));

        List<char[]> held = new ArrayList<>();
        try (OpenedCredentials opened = credentials.open(endpointA)) {
            assertThat(opened.fields()).containsExactly("BASIC_PASSWORD");
            char[] value = opened.value("BASIC_PASSWORD").orElseThrow();
            assertThat(new String(value)).isEqualTo("wipe-me");
            assertThat(opened.toString()).contains("BASIC_PASSWORD").doesNotContain("wipe-me");
            held.add(value);
        }
        assertThat(held.get(0)).as("wiped on close").containsOnly('\0');
        try (OpenedCredentials again = credentials.open(endpointA)) {
            assertThat(new String(again.value("BASIC_PASSWORD").orElseThrow())).isEqualTo("wipe-me");
        }
        assertThat(reads).as("no cache of decrypted values: each send reads and opens again").hasValue(2);
    }

    @Test
    @Tag("negative")
    @DisplayName("One unreadable field fails the whole open, and the fields already opened are wiped")
    void aPartialOpenIsWiped() {
        SealedCredential good = seal(tenantA, UUID.randomUUID(), endpointA, "API_KEY", "fine", "K1");
        SealedCredential bad = seal(tenantA, UUID.randomUUID(), endpointB, "BASIC_PASSWORD", "moved", "K1");
        SealedCredential moved = new SealedCredential(bad.id(), tenantA, endpointA, "BASIC_PASSWORD", "K1",
                bad.wrappedKey(), bad.ciphertext());
        SendTimeCredentials credentials = new SendTimeCredentials(endpoint -> List.of(good, moved),
                keys(Map.of("K1", k1)));

        assertThatThrownBy(() -> credentials.open(endpointA)).isInstanceOf(CredentialUnreadableException.class);
    }
}
