package io.integrationplatform.delivery.internal.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.delivery.port.DeliveryReconcileStore;
import io.integrationplatform.delivery.port.DeliveryReconcileStore.SendingClaim;
import io.integrationplatform.delivery.port.InstanceReadiness;
import io.integrationplatform.delivery.port.TargetRedriveCap;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.ingest.port.StageTimings;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Lane deliver-deadletter, path 2: the reconciler sweep's AMBIGUOUS dead letter (T-180, ADR-010 section 6) goes
 * through the real {@link JdbcDeliveryRetryStore} and therefore records the DELIVER timeline stage, once, at the
 * move's own instant, with the tenant bound by the trigger's context; an overtaken claim (0 rows) records nothing.
 */
class DeliveryRetryTriggerDeadLetterStageTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    private static final UUID DELIVERY = UUID.fromString("dddddddd-0000-0000-0000-0000000000d1");
    private static final UUID TOKEN = UUID.fromString("cccccccc-0000-0000-0000-0000000000c1");
    private static final UUID TARGET = UUID.fromString("eeeeeeee-0000-0000-0000-0000000000e1");

    private final TriggerTenantContext context = new TriggerTenantContext();
    private final JdbcDeliveryRepository repository = mock(JdbcDeliveryRepository.class);
    private final StageTimings timings = mock(StageTimings.class);
    private final DeliveryReconcileStore reconcile = mock(DeliveryReconcileStore.class);
    private final JdbcDeliveryRetryStore store = spy(
            new JdbcDeliveryRetryStore(repository, mock(JdbcClient.class), context, timings));

    private DeliveryRetryTrigger trigger() {
        doReturn(List.of()).when(store).lockDue(any(), anyInt());
        when(reconcile.sendingClaimedBefore(any(), anyInt())).thenReturn(List.of(
                new SendingClaim(DELIVERY, TOKEN, Instant.now().minus(Duration.ofMinutes(30)), TARGET, 1)));
        return new DeliveryRetryTrigger(() -> List.of(TENANT), context, TransactionOperations.withoutTransaction(),
                store, mock(OutboxWriter.class), 50, InstanceReadiness.alwaysReady(), delivery -> Optional.empty(),
                TargetRedriveCap.unlimited(), reconcile, target -> Optional.empty(), RetryPolicy.defaults());
    }

    @Test
    void aStaleClaimOnAnUnknownTargetDeadLettersAmbiguousAndRecordsTheDeliverStageOnce() {
        when(repository.deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.AMBIGUOUS), any())).thenReturn(true);

        trigger().runOnce();

        ArgumentCaptor<Instant> moveAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.AMBIGUOUS), moveAt.capture());
        verify(timings, times(1)).recordDeliverFinished(DELIVERY, moveAt.getValue());
        assertThat(context.currentTenantId()).as("the tenant is the trigger's per-transaction bind, unbound after")
                .isEmpty();
    }

    @Test
    void anOvertakenClaimDeadLettersNothingAndRecordsNoStage() {
        when(repository.deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.AMBIGUOUS), any())).thenReturn(false);

        trigger().runOnce();

        verifyNoInteractions(timings);
    }
}
