package io.integrationplatform.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The DELIVER timeline stage for every dead letter that goes through the retry store (T105 follow-up, lane
 * deliver-deadletter). The store's two {@code deadLetter} overloads are the only writers of {@code DEAD_LETTERED}
 * besides the send-outcome recorder (which has its own, tested stage write): the credential dead letter (T-185,
 * four-argument form, called by {@code CredentialedDelivery}) and the reconciler's AMBIGUOUS stale-claim dead letter
 * (three-argument form, called by {@code DeliveryRetryTrigger}). Each must hand the delivery to the stage timing log
 * exactly once, at the instant of the move itself, only when the move matched a row, in the caller's transaction.
 * The tenant is never passed: the writer reads it from the stamped context, and the interface has no tenant
 * parameter to get wrong. The real rows are proven by platform-routing's {@code DeadLetterDeliverStageContainerTest}.
 */
class JdbcDeliveryRetryStoreDeadLetterStageTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000dead");
    private static final UUID DELIVERY = UUID.fromString("dddddddd-0000-0000-0000-00000000dead");
    private static final UUID TOKEN = UUID.fromString("cccccccc-0000-0000-0000-00000000dead");
    private static final String REASON = "target credential unreadable";

    private final TenantContext tenantContext = () -> Optional.of(TENANT);
    private final JdbcDeliveryRepository repository = mock(JdbcDeliveryRepository.class);
    private final StageTimings timings = mock(StageTimings.class);
    private final JdbcDeliveryRetryStore store =
            new JdbcDeliveryRetryStore(repository, mock(JdbcClient.class), tenantContext, timings);

    @Test
    @DisplayName("the credential dead letter (named reason) records DELIVER once, at the move instant")
    void theCredentialDeadLetterRecordsTheDeliverStageAtTheMoveInstant() {
        when(repository.deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.PERMANENT), eq(REASON), any()))
                .thenReturn(true);

        assertThat(store.deadLetter(DELIVERY, TOKEN, OutcomeClass.PERMANENT, REASON)).isTrue();

        ArgumentCaptor<Instant> moveAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.PERMANENT), eq(REASON),
                moveAt.capture());
        verify(timings).recordDeliverFinished(DELIVERY, moveAt.getValue());
        verifyNoMoreInteractions(timings);
    }

    @Test
    @DisplayName("the reconciler AMBIGUOUS dead letter records DELIVER once, at the move instant")
    void theAmbiguousStaleClaimDeadLetterRecordsTheDeliverStageAtTheMoveInstant() {
        when(repository.deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.AMBIGUOUS), any())).thenReturn(true);

        assertThat(store.deadLetter(DELIVERY, TOKEN, OutcomeClass.AMBIGUOUS)).isTrue();

        ArgumentCaptor<Instant> moveAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.AMBIGUOUS), moveAt.capture());
        verify(timings).recordDeliverFinished(DELIVERY, moveAt.getValue());
        verifyNoMoreInteractions(timings);
    }

    @Test
    @DisplayName("the stage is written after the move matched, in the same call")
    void theStageFollowsTheMove() {
        when(repository.deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.PERMANENT), any())).thenReturn(true);

        store.deadLetter(DELIVERY, TOKEN, OutcomeClass.PERMANENT);

        InOrder order = inOrder(repository, timings);
        order.verify(repository).deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.PERMANENT), any());
        order.verify(timings).recordDeliverFinished(eq(DELIVERY), any());
    }

    @Test
    @DisplayName("a stale or foreign claim moves nothing: LATE_OUTCOME is recorded and no stage row is written")
    void aStaleClaimWritesNoStage() {
        when(repository.deadLetter(any(), any(), any(OutcomeClass.class), any(Instant.class))).thenReturn(false);
        when(repository.deadLetter(any(), any(), any(OutcomeClass.class), any(String.class), any(Instant.class)))
                .thenReturn(false);

        assertThat(store.deadLetter(DELIVERY, TOKEN, OutcomeClass.AMBIGUOUS)).isFalse();
        assertThat(store.deadLetter(DELIVERY, TOKEN, OutcomeClass.PERMANENT, REASON)).isFalse();

        verify(repository, times(2)).appendEvent(eq(DELIVERY), eq(DeliveryEventType.LATE_OUTCOME), any(), any());
        verifyNoInteractions(timings);
    }

    @Test
    @DisplayName("a repeat of a dead letter that already happened (0 rows) writes nothing the second time")
    void aRepeatedDeadLetterWritesNothingTheSecondTime() {
        when(repository.deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.AMBIGUOUS), any()))
                .thenReturn(true, false);

        store.deadLetter(DELIVERY, TOKEN, OutcomeClass.AMBIGUOUS);
        store.deadLetter(DELIVERY, TOKEN, OutcomeClass.AMBIGUOUS);

        verify(timings, times(1)).recordDeliverFinished(eq(DELIVERY), any());
    }

    @Test
    @DisplayName("a store built without timings (a bare slice) still dead-letters and records no stage")
    void aStoreWithoutTimingsStillMovesTheDelivery() {
        JdbcDeliveryRetryStore bare = new JdbcDeliveryRetryStore(repository, mock(JdbcClient.class), tenantContext,
                null);
        when(repository.deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.PERMANENT), eq(REASON), any()))
                .thenReturn(true);

        assertThat(bare.deadLetter(DELIVERY, TOKEN, OutcomeClass.PERMANENT, REASON)).isTrue();
        verify(timings, never()).recordDeliverFinished(any(), any());
    }
}
