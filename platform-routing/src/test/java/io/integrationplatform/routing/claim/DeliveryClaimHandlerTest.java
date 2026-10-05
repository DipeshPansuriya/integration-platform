package io.integrationplatform.routing.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.delivery.DeliveryClaimService;
import io.integrationplatform.delivery.DeliveryClaimService.Outcome;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository.Claimable;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-193 (E2-F2-S4): the stage-2 claim is ONE transaction running the ADR-010 section 7 order - ordering-key
 * lock, delivery read {@code FOR UPDATE}, hold check, then {@code HELD} or {@code SENDING} - and only that
 * order, never a wider effect when nothing matched. The database-side proof (real locks, real commit) is
 * platform-delivery's container suite; this test pins the handler and the service's step sequence.
 */
class DeliveryClaimHandlerTest {

    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final JdbcDeliveryRepository repository = mock(JdbcDeliveryRepository.class);
    private final DeliveryClaimHandler handler =
            new DeliveryClaimHandler(new TransactionTemplate(txManager), new DeliveryClaimService(repository));

    private final UUID deliveryId = UUID.randomUUID();
    private final UUID token = UUID.randomUUID();

    @BeforeEach
    void stubTransaction() {
        when(txManager.getTransaction(any())).thenReturn(status);
    }

    private void claimablePresent() {
        when(repository.readForClaim(deliveryId)).thenReturn(Optional.of(
                new Claimable(deliveryId, UUID.randomUUID(), "bk", 4L, UUID.randomUUID())));
    }

    @Test
    void theClaimLocksChecksAndSetsSendingInOneTransaction() {
        claimablePresent();
        when(repository.holdApplies(any())).thenReturn(false);
        when(repository.claimSend(any(), any(), any(Instant.class))).thenReturn(true);

        assertThat(handler.claim("t|iface|bk", deliveryId, token)).isEqualTo(Outcome.CLAIMED);

        InOrder order = inOrder(repository);
        order.verify(repository).lockOrderingKey("t|iface|bk");
        order.verify(repository).readForClaim(deliveryId);
        order.verify(repository).holdApplies(any());
        order.verify(repository).claimSend(eq(deliveryId), eq(token), any(Instant.class));
        verify(txManager).commit(status);
        verify(txManager, never()).rollback(any());
    }

    @Test
    void aBlockedClaimSetsHeldAndNeverSends() {
        claimablePresent();
        when(repository.holdApplies(any())).thenReturn(true);
        when(repository.hold(any(), any(Instant.class))).thenReturn(true);

        assertThat(handler.claim("t|iface|bk", deliveryId, token)).isEqualTo(Outcome.HELD);

        InOrder order = inOrder(repository);
        order.verify(repository).lockOrderingKey("t|iface|bk");
        order.verify(repository).readForClaim(deliveryId);
        order.verify(repository).holdApplies(any());
        order.verify(repository).hold(any(), any(Instant.class));
        verify(repository, never()).claimSend(any(), any(), any());
        verify(txManager).commit(status);
    }

    @Test
    void aDuplicateRecordAcknowledgesWithNoEffect() {
        when(repository.readForClaim(deliveryId)).thenReturn(Optional.empty());

        assertThat(handler.claim("t|iface|bk", deliveryId, token)).isEqualTo(Outcome.NO_EFFECT);

        verify(repository).lockOrderingKey("t|iface|bk");
        verify(repository, never()).holdApplies(any());
        verify(repository, never()).claimSend(any(), any(), any());
        verify(repository, never()).hold(any(), any());
        verify(txManager).commit(status); // acknowledging the duplicate is a clean, empty transaction
    }

    @Test
    void anOrderingNoneInterfaceSkipsTheAdvisoryLock() {
        claimablePresent();
        when(repository.holdApplies(any())).thenReturn(false);
        when(repository.claimSend(any(), any(), any(Instant.class))).thenReturn(true);

        assertThat(handler.claim(null, deliveryId, token)).isEqualTo(Outcome.CLAIMED);
        assertThat(handler.claim(" ", deliveryId, token)).isEqualTo(Outcome.CLAIMED);

        verify(repository, never()).lockOrderingKey(anyString());
    }

    @Test
    void aFailedStepRollsTheWholeTransactionBack() {
        claimablePresent();
        when(repository.holdApplies(any())).thenReturn(false);
        when(repository.claimSend(any(), any(), any(Instant.class))).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> handler.claim("t|iface|bk", deliveryId, token))
                .isInstanceOf(IllegalStateException.class);

        verify(txManager).rollback(status);
        verify(txManager, never()).commit(any());
    }
}
