package io.integrationplatform.routing.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.credential.api.KeyAvailability;
import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.api.SealedCredential;
import io.integrationplatform.config.credential.crypto.EnvelopeSealer;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.credential.CredentialedDelivery;
import io.integrationplatform.routing.credential.CredentialedDelivery.Result;
import io.integrationplatform.routing.credential.SendTimeCredentials;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionOperations;

/**
 * E5-F1-S4 (T-185; ADR-007 Decision 7 rules 5 and 6; ADR-010 section 5) at the send step: the target credential is
 * opened at send time and the two decryption failures go two different ways.
 *
 * <pre>
 * Scenario: Unknown key-encryption-key id is transient
 *   Given an instance started without the key id that a target credential references
 *   When a delivery to that target is attempted
 *   Then the outcome is TRANSIENT, the delivery waits in RETRY_WAIT, an alert fires, and the instance fails its
 *        readiness check
 * (here: no call is made, the store's KEY_UNAVAILABLE deferral is the one move, the alert is the structured
 *  event=credential.key_unavailable line, and the missing id is reported to the readiness check)
 *
 * &#64;negative &#64;security
 * Scenario: Tag mismatch is permanent
 *   Given a target credential ciphertext copied from another tenant's row
 *   When it is decrypted at send time
 *   Then the delivery is DEAD_LETTERED with reason "target credential unreadable", and neither ciphertext nor
 *        plaintext is logged
 * </pre>
 * The real tables are proven by platform-delivery's KeyFaultMovesContainerTest and platform-app's
 * E5KeyFaultContainerTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class CredentialedDeliveryTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey k1 = aesKey();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID endpoint = UUID.randomUUID();
    private final UUID delivery = UUID.randomUUID();
    private final UUID token = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-11T12:00:00Z");

    private final DeliveryRetryStore store = mock(DeliveryRetryStore.class);
    private final List<String> reportedMissing = new ArrayList<>();
    private final KeyAvailability availability = reportedMissing::add;

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
                return Optional.of("K2");
            }
        };
    }

    private SealedCredential seal(UUID tenant, UUID credentialId, UUID targetEndpoint, String value, String kekId) {
        EnvelopeSealer.Sealed sealed = EnvelopeSealer.seal(value.toCharArray(), tenant, credentialId, targetEndpoint,
                "BASIC_PASSWORD", kekId, k1);
        return new SealedCredential(credentialId, tenant, targetEndpoint, "BASIC_PASSWORD", kekId, sealed.wrappedKey(),
                sealed.ciphertext());
    }

    private CredentialedDelivery delivery(List<SealedCredential> rows, KeyEncryptionKeys keys) {
        return new CredentialedDelivery(new SendTimeCredentials(target -> rows, keys), availability, store,
                TransactionOperations.withoutTransaction(), () -> 0.5, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Scenario: Unknown key id - TRANSIENT, no call, RETRY_WAIT with KEY_UNAVAILABLE, alert, readiness told")
    void unknownKeyIdIsTransient(CapturedOutput output) {
        SealedCredential sealed = seal(tenantA, UUID.randomUUID(), endpoint, "never-sent", "K1");
        when(store.deferKeyUnavailable(delivery, token, now, 0.5)).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();

        Result<String> result = delivery(List.of(sealed), keys(Map.of("K2", aesKey())))
                .send(delivery, token, endpoint, opened -> {
                    calls.incrementAndGet();
                    return "sent";
                });

        assertThat(result.kind()).isEqualTo(CredentialedDelivery.Kind.KEY_UNAVAILABLE);
        assertThat(result.outcomeClass()).isEqualTo(OutcomeClass.TRANSIENT);
        assertThat(calls).as("no call is made").hasValue(0);
        verify(store).deferKeyUnavailable(delivery, token, now, 0.5);
        verify(store, never()).deadLetter(any(), any(), any(OutcomeClass.class), anyString());
        verify(store, never()).scheduleRetry(any(), any(), any());
        verifyNoMoreInteractions(store);
        assertThat(reportedMissing).containsExactly("K1");
        assertThat(output).contains("event=credential.key_unavailable").contains("alert=true").contains("kekId=K1")
                .contains(delivery.toString()).doesNotContain("never-sent");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: Tag mismatch - a ciphertext copied from another tenant's row dead-letters, nothing logged")
    void tagMismatchIsPermanent(CapturedOutput output) {
        SealedCredential ofTenantB = seal(tenantB, UUID.randomUUID(), UUID.randomUUID(), "tenant-b-secret", "K1");
        UUID rowOfA = UUID.randomUUID();
        SealedCredential copied = new SealedCredential(rowOfA, tenantA, endpoint, "BASIC_PASSWORD", "K1",
                ofTenantB.wrappedKey(), ofTenantB.ciphertext());
        when(store.deadLetter(delivery, token, OutcomeClass.PERMANENT, "target credential unreadable"))
                .thenReturn(true);
        AtomicInteger calls = new AtomicInteger();

        Result<String> result = delivery(List.of(copied), keys(Map.of("K1", k1)))
                .send(delivery, token, endpoint, opened -> {
                    calls.incrementAndGet();
                    return "sent";
                });

        assertThat(result.kind()).isEqualTo(CredentialedDelivery.Kind.UNREADABLE);
        assertThat(result.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT);
        assertThat(calls).hasValue(0);
        verify(store).deadLetter(delivery, token, OutcomeClass.PERMANENT, "target credential unreadable");
        verify(store, never()).deferKeyUnavailable(any(), any(), any(), anyDouble());
        assertThat(reportedMissing).as("a tag mismatch is not a missing key").isEmpty();
        assertThat(output).contains("event=credential.unreadable").contains("alert=true")
                .contains(rowOfA.toString());
        for (String leaked : List.of("tenant-b-secret",
                Base64.getEncoder().encodeToString(copied.ciphertext()),
                Base64.getEncoder().encodeToString(copied.wrappedKey()),
                HexFormat.of().formatHex(copied.ciphertext()),
                HexFormat.of().formatHex(copied.wrappedKey()))) {
            assertThat(output.getAll()).as("neither ciphertext nor plaintext is logged").doesNotContain(leaked);
        }
    }

    @Test
    @DisplayName("A readable credential reaches the call in memory only; the state moves stay the classifier's")
    void aReadableCredentialIsSent(CapturedOutput output) {
        SealedCredential sealed = seal(tenantA, UUID.randomUUID(), endpoint, "fine-to-send", "K1");
        List<char[]> seen = new ArrayList<>();

        Result<String> result = delivery(List.of(sealed), keys(Map.of("K1", k1)))
                .send(delivery, token, endpoint, opened -> {
                    char[] value = opened.value("BASIC_PASSWORD").orElseThrow();
                    seen.add(value);
                    return new String(value);
                });

        assertThat(result.kind()).isEqualTo(CredentialedDelivery.Kind.SENT);
        assertThat(result.value()).isEqualTo("fine-to-send");
        assertThat(seen.get(0)).as("wiped once the call returned").containsOnly('\0');
        verifyNoMoreInteractions(store);
        assertThat(output).doesNotContain("fine-to-send");
    }

    @Test
    @Tag("negative")
    @DisplayName("A delivery whose claim was already overtaken: the store says no effect, nothing else happens")
    void anOvertakenClaimHasNoEffect() {
        SealedCredential sealed = seal(tenantA, UUID.randomUUID(), endpoint, "x", "K1");
        when(store.deferKeyUnavailable(eq(delivery), eq(token), any(), anyDouble())).thenReturn(false);

        Result<String> result = delivery(List.of(sealed), keys(Map.of())).send(delivery, token, endpoint, o -> "x");

        assertThat(result.kind()).isEqualTo(CredentialedDelivery.Kind.KEY_UNAVAILABLE);
        assertThat(reportedMissing).containsExactly("K1");
    }
}
