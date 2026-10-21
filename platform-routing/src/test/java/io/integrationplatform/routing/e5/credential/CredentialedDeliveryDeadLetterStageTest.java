package io.integrationplatform.routing.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.credential.api.KeyAvailability;
import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.api.SealedCredential;
import io.integrationplatform.config.credential.crypto.EnvelopeSealer;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.routing.credential.CredentialedDelivery;
import io.integrationplatform.routing.credential.SendTimeCredentials;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Lane deliver-deadletter, path 1: the credential dead letter (T-185; a tag mismatch is PERMANENT, "target credential
 * unreadable") runs through the real {@link JdbcDeliveryRetryStore} over a mocked repository and records the DELIVER
 * timeline stage once, at the move's own instant; an overtaken claim (0 rows) records none, and a key-unavailable
 * deferral (a wait, not a final move) records none. The real rows are proven by
 * {@code DeadLetterDeliverStageContainerTest}.
 */
class CredentialedDeliveryDeadLetterStageTest {

    private static final String REASON = "target credential unreadable";

    private final SecretKey k1 = aesKey();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID endpoint = UUID.randomUUID();
    private final UUID delivery = UUID.randomUUID();
    private final UUID token = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-22T12:00:00Z");

    private final JdbcDeliveryRepository repository = mock(JdbcDeliveryRepository.class);
    private final StageTimings timings = mock(StageTimings.class);
    private final JdbcDeliveryRetryStore store = new JdbcDeliveryRetryStore(repository, mock(JdbcClient.class),
            () -> Optional.of(tenantA), timings);
    private final KeyAvailability availability = kekId -> { };

    private static SecretKey aesKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new SecretKeySpec(bytes, "AES");
    }

    /** A credential row copied from tenant B's row into tenant A's: the authentication tag cannot match. */
    private SealedCredential copiedFromAnotherTenant() {
        EnvelopeSealer.Sealed sealed = EnvelopeSealer.seal("tenant-b-secret".toCharArray(), tenantB,
                UUID.randomUUID(), UUID.randomUUID(), "BASIC_PASSWORD", "K1", k1);
        return new SealedCredential(UUID.randomUUID(), tenantA, endpoint, "BASIC_PASSWORD", "K1",
                sealed.wrappedKey(), sealed.ciphertext());
    }

    private CredentialedDelivery credentialed(List<SealedCredential> rows, boolean keyKnown) {
        KeyEncryptionKeys keys = new KeyEncryptionKeys() {
            @Override
            public Optional<SecretKey> find(String kekId) {
                return keyKnown ? Optional.of(k1) : Optional.empty();
            }

            @Override
            public Optional<String> activeId() {
                return Optional.of("K1");
            }
        };
        return new CredentialedDelivery(new SendTimeCredentials(target -> rows, keys), availability, store,
                TransactionOperations.withoutTransaction(), () -> 0.5, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void theCredentialDeadLetterRecordsTheDeliverStageOnceAtTheMoveInstant() {
        when(repository.deadLetter(eq(delivery), eq(token), eq(OutcomeClass.PERMANENT), eq(REASON), any()))
                .thenReturn(true);

        var result = credentialed(List.of(copiedFromAnotherTenant()), true)
                .send(delivery, token, endpoint, opened -> "never-sent");

        assertThat(result.kind()).isEqualTo(CredentialedDelivery.Kind.UNREADABLE);
        ArgumentCaptor<Instant> moveAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deadLetter(eq(delivery), eq(token), eq(OutcomeClass.PERMANENT), eq(REASON),
                moveAt.capture());
        verify(timings, times(1)).recordDeliverFinished(delivery, moveAt.getValue());
    }

    @Test
    void anOvertakenClaimRecordsNoDeliverStage() {
        when(repository.deadLetter(eq(delivery), eq(token), eq(OutcomeClass.PERMANENT), eq(REASON), any()))
                .thenReturn(false);

        credentialed(List.of(copiedFromAnotherTenant()), true).send(delivery, token, endpoint, opened -> "never-sent");

        verifyNoInteractions(timings);
    }

    @Test
    void aKeyUnavailableDeferralIsNotAFinalMoveAndRecordsNoDeliverStage() {
        when(repository.deferKeyUnavailable(eq(delivery), eq(token), any(), any())).thenReturn(true);

        var result = credentialed(List.of(copiedFromAnotherTenant()), false)
                .send(delivery, token, endpoint, opened -> "never-sent");

        assertThat(result.kind()).isEqualTo(CredentialedDelivery.Kind.KEY_UNAVAILABLE);
        verifyNoInteractions(timings);
    }
}
