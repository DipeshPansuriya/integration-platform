package io.integrationplatform.delivery.internal.stage1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.ingest.port.StageTimings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The ROUTE row is written by the flip that happened, once; a re-consume flips nothing and writes nothing. */
class JdbcStage1DeliveriesRouteStageTest {

    private static final UUID MESSAGE = UUID.nameUUIDFromBytes("message-1".getBytes(StandardCharsets.UTF_8));

    private final JdbcDeliveryRepository repository = mock(JdbcDeliveryRepository.class);
    private final StageTimings timings = mock(StageTimings.class);
    private final JdbcStage1Deliveries deliveries = new JdbcStage1Deliveries(repository, timings);

    @Test
    void theFlipRecordsRouteOnce() {
        when(repository.markMessageRouted(MESSAGE)).thenReturn(true);

        deliveries.markMessageRouted(MESSAGE);

        verify(timings).record(eq(MESSAGE), eq(StageTimings.Stage.ROUTE), any(Instant.class), any(Instant.class));
    }

    @Test
    void aReconsumeFlipsNothingAndRecordsNothing() {
        when(repository.markMessageRouted(MESSAGE)).thenReturn(false);

        deliveries.markMessageRouted(MESSAGE);

        verify(timings, never()).record(any(), any(), any(), any());
    }
}
