package io.integrationplatform.app.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.tracking.api.DeliveryTime;
import io.integrationplatform.tracking.api.Failure;
import io.integrationplatform.tracking.api.StageTime;
import io.integrationplatform.tracking.api.Timeline;
import io.integrationplatform.tracking.api.TrackingReadApi;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcTrackingReadApiTest {

    @Test
    void timelineMapsTheServiceTimelineOntoTheContractTimeline() {
        MessageTrackingService service = mock(MessageTrackingService.class);
        JdbcTrackingReadApi api = new JdbcTrackingReadApi(service);
        UUID trackingId = UUID.randomUUID();
        MessageTrackingService.StageTime stage =
                new MessageTrackingService.StageTime("ROUTE", "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z", 1000L, 0L);
        MessageTrackingService.DeliveryTime delivery =
                new MessageTrackingService.DeliveryTime("d", "t", 50L, "OK", DeliveryState.DELIVERED);
        MessageTrackingService.Timeline source =
                new MessageTrackingService.Timeline(trackingId.toString(), "flow", "DELIVERED", "2026-01-01T00:00:00Z",
                        List.of(stage), 1000L, null, List.of(delivery));
        when(service.timeline(trackingId)).thenReturn(Optional.of(source));

        Optional<Timeline> result = api.timeline(trackingId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(new Timeline(trackingId.toString(), "flow", "DELIVERED",
                "2026-01-01T00:00:00Z", List.of(new StageTime("ROUTE", "2026-01-01T00:00:00Z",
                        "2026-01-01T00:00:01Z", 1000L, 0L)), 1000L, null,
                List.of(new DeliveryTime("d", "t", 50L, "OK", DeliveryState.DELIVERED))));
    }

    @Test
    void failuresMapTheServiceFailuresOntoTheContractFailures() {
        MessageTrackingService service = mock(MessageTrackingService.class);
        JdbcTrackingReadApi api = new JdbcTrackingReadApi(service);
        LocalDate day = LocalDate.of(2026, 1, 1);
        UUID flowId = UUID.randomUUID();
        MessageTrackingService.Failure source = new MessageTrackingService.Failure("tid", "did", "flow", "tgt", "DELIVER",
                "DEAD_LETTERED", "err", "PLATFORM", "2026-01-01T00:00:00Z");
        when(service.failures(flowId, day)).thenReturn(List.of(source));

        List<Failure> result = api.failures(flowId, day);

        assertThat(result).containsExactly(new Failure("tid", "did", "flow", "tgt", "DELIVER", "DEAD_LETTERED", "err",
                "PLATFORM", "2026-01-01T00:00:00Z"));
    }

    @Test
    void nullListsStayNull() {
        MessageTrackingService service = mock(MessageTrackingService.class);
        JdbcTrackingReadApi api = new JdbcTrackingReadApi(service);
        UUID trackingId = UUID.randomUUID();
        MessageTrackingService.StageTime stage =
                new MessageTrackingService.StageTime("ROUTE", "s", "f", 1L, 0L);
        MessageTrackingService.Timeline source = new MessageTrackingService.Timeline(trackingId.toString(), "flow",
                "DELIVERED", "a", List.of(stage), 1L, null, null);
        when(service.timeline(trackingId)).thenReturn(Optional.of(source));

        Timeline result = api.timeline(trackingId).orElseThrow();

        assertThat(result.stages()).hasSize(1);
        assertThat(result.deliveries()).isNull();
    }
}
