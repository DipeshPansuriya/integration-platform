package io.integrationplatform.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.delivery.internal.retry.OutcomeRecorder;
import io.integrationplatform.ingest.port.StageTimings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E13/E18 gap (lane tracking-stages): the success move of the send path records the message's DELIVER stage, in the
 * move's own (the caller's) transaction, and only when the move matched - a redelivered record finds the delivery
 * already DELIVERED (0 rows), so it writes no second timing. The tenant is the writer's, from the stamped context.
 */
class JdbcSendOutcomesDeliverStageTest {

    private static final UUID DELIVERY = UUID.nameUUIDFromBytes("delivery-1".getBytes(StandardCharsets.UTF_8));
    private static final UUID TOKEN = UUID.nameUUIDFromBytes("claim-1".getBytes(StandardCharsets.UTF_8));

    private final JdbcDeliveryRepository repository = mock(JdbcDeliveryRepository.class);
    private final StageTimings timings = mock(StageTimings.class);
    private final JdbcSendOutcomes outcomes = new JdbcSendOutcomes(repository, mock(OutcomeRecorder.class), timings);

    @Test
    void aDeliveredMoveRecordsTheDeliverStageOnceFinishedAtTheMove() {
        when(repository.markDelivered(eq(DELIVERY), eq(TOKEN), any(Instant.class))).thenReturn(true);

        assertThat(outcomes.markDelivered(DELIVERY, TOKEN)).isTrue();

        verify(timings).recordDeliverFinished(eq(DELIVERY), any(Instant.class));
    }

    @Test
    void theInstantOfTheStateMoveIsTheInstantOfTheTiming() {
        when(repository.markDelivered(eq(DELIVERY), eq(TOKEN), any(Instant.class))).thenReturn(true);
        ArgumentCaptor<Instant> moved = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> timed = ArgumentCaptor.forClass(Instant.class);

        outcomes.markDelivered(DELIVERY, TOKEN);

        verify(repository).markDelivered(eq(DELIVERY), eq(TOKEN), moved.capture());
        verify(timings).recordDeliverFinished(eq(DELIVERY), timed.capture());
        assertThat(timed.getValue()).isEqualTo(moved.getValue());
    }

    @Test
    void aStaleOrRedeliveredClaimMovedNothingAndRecordsNothing() {
        when(repository.markDelivered(eq(DELIVERY), eq(TOKEN), any(Instant.class))).thenReturn(false);

        assertThat(outcomes.markDelivered(DELIVERY, TOKEN)).isFalse();

        verify(timings, never()).recordDeliverFinished(any(), any());
    }

    @Test
    void aBareSliceWithoutATimingWriterStillMovesTheDelivery() {
        when(repository.markDelivered(eq(DELIVERY), eq(TOKEN), any(Instant.class))).thenReturn(true);
        JdbcSendOutcomes bare = new JdbcSendOutcomes(repository, mock(OutcomeRecorder.class), null);

        assertThat(bare.markDelivered(DELIVERY, TOKEN)).isTrue();
    }
}
