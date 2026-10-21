package io.integrationplatform.app.message;

import io.integrationplatform.tracking.api.DeliveryTime;
import io.integrationplatform.tracking.api.Failure;
import io.integrationplatform.tracking.api.StageTime;
import io.integrationplatform.tracking.api.Timeline;

/**
 * The one mapping from {@link MessageTrackingService}'s records onto the {@code platform-tracking} contract types,
 * shared by {@link TrackingReadApiAdapter} and the report adapter, so a field added to the contract cannot be
 * mapped in one adapter and missed in the other. Absent lists stay absent (null), as the contract allows.
 */
public final class TrackingContractMapper {

    private TrackingContractMapper() {
    }

    public static Timeline timeline(MessageTrackingService.Timeline source) {
        return new Timeline(source.trackingId(), source.flowId(), source.state(), source.acceptedAt(),
                source.stages() == null ? null : source.stages().stream().map(TrackingContractMapper::stage).toList(),
                source.totalMs(), source.failingStage(),
                source.deliveries() == null ? null
                        : source.deliveries().stream().map(TrackingContractMapper::delivery).toList());
    }

    public static StageTime stage(MessageTrackingService.StageTime source) {
        return new StageTime(source.stage(), source.startedAt(), source.finishedAt(), source.durationMs(),
                source.sincePreviousMs());
    }

    public static DeliveryTime delivery(MessageTrackingService.DeliveryTime source) {
        return new DeliveryTime(source.deliveryId(), source.targetId(), source.durationMs(), source.outcome(),
                source.state());
    }

    public static Failure failure(MessageTrackingService.Failure source) {
        return new Failure(source.trackingId(), source.deliveryId(), source.flowId(), source.targetId(),
                source.failingStage(), source.errorClass(), source.lastError(), source.origin(), source.failedAt());
    }
}
