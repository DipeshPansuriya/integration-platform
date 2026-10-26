package io.integrationplatform.app.message;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.tracking.api.DeliveryTime;
import io.integrationplatform.tracking.api.Failure;
import io.integrationplatform.tracking.api.StageTime;
import io.integrationplatform.tracking.api.Timeline;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The one shared mapping from the tracking service's records onto the platform-tracking contract (review 7). */
class TrackingContractMapperTest {

    @Test
    void timelineCarriesEveryFieldIncludingStagesAndDeliveries() {
        MessageTrackingService.Timeline source = new MessageTrackingService.Timeline("t", "flow", "DELIVERED",
                "2026-01-01T00:00:00Z",
                List.of(new MessageTrackingService.StageTime("ROUTE", "s", "f", 1000L, 2L)), 1000L, "ROUTE",
                List.of(new MessageTrackingService.DeliveryTime("d", "tg", 50L, "OK", DeliveryState.DELIVERED)));

        assertThat(TrackingContractMapper.timeline(source)).isEqualTo(new Timeline("t", "flow", "DELIVERED",
                "2026-01-01T00:00:00Z", List.of(new StageTime("ROUTE", "s", "f", 1000L, 2L)), 1000L, "ROUTE",
                List.of(new DeliveryTime("d", "tg", 50L, "OK", DeliveryState.DELIVERED))));
    }

    @Test
    void absentStageAndDeliveryListsStayAbsent() {
        MessageTrackingService.Timeline source = new MessageTrackingService.Timeline("t", "flow", "ACCEPTED", "a",
                null, null, null, null);

        Timeline mapped = TrackingContractMapper.timeline(source);

        assertThat(mapped.stages()).isNull();
        assertThat(mapped.deliveries()).isNull();
    }

    @Test
    void failureCarriesEveryField() {
        MessageTrackingService.Failure source = new MessageTrackingService.Failure("tid", "did", "flow", "tgt",
                "DELIVER", "DEAD_LETTERED", "err", "PLATFORM", "2026-01-01T00:00:00Z");

        assertThat(TrackingContractMapper.failure(source)).isEqualTo(new Failure("tid", "did", "flow", "tgt",
                "DELIVER", "DEAD_LETTERED", "err", "PLATFORM", "2026-01-01T00:00:00Z"));
    }

    @Test
    void stageCarriesEveryField() {
        assertThat(TrackingContractMapper.stage(new MessageTrackingService.StageTime("ROUTE", "s", "f", 1L, 2L)))
                .isEqualTo(new StageTime("ROUTE", "s", "f", 1L, 2L));
    }
}
