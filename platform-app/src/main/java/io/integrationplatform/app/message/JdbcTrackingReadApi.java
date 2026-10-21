package io.integrationplatform.app.message;

import io.integrationplatform.tracking.api.DeliveryTime;
import io.integrationplatform.tracking.api.Failure;
import io.integrationplatform.tracking.api.StageTime;
import io.integrationplatform.tracking.api.Timeline;
import io.integrationplatform.tracking.api.TrackingReadApi;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Behavior-backed runtime slice (E13): the {@link TrackingReadApi} contract backed by
 * {@link MessageTrackingService}, so the low-conflict {@code platform-tracking} contract is exercised
 * against the real (tenant-scoped) behavior instead of being a compile-time guard only.
 *
 * <p>This adapter is the one place that crosses from the shared contract module into {@code platform-app}; it
 * delegates all reading to {@link MessageTrackingService} and does no reading of its own, mirroring
 * {@code JdbcActivityReadApi}.
 */
public final class JdbcTrackingReadApi implements TrackingReadApi {

    private final MessageTrackingService service;

    public JdbcTrackingReadApi(MessageTrackingService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public Optional<Timeline> timeline(UUID trackingId) {
        return service.timeline(trackingId).map(JdbcTrackingReadApi::timeline);
    }

    @Override
    public List<Failure> failures(UUID flowId, LocalDate day) {
        List<MessageTrackingService.Failure> source = service.failures(flowId, day);
        if (source == null) {
            return List.of();
        }
        return source.stream().map(JdbcTrackingReadApi::failure).toList();
    }

    private static Timeline timeline(MessageTrackingService.Timeline source) {
        return new Timeline(source.trackingId(), source.flowId(), source.state(), source.acceptedAt(),
                source.stages() == null ? null : source.stages().stream().map(JdbcTrackingReadApi::stage).toList(),
                source.totalMs(), source.failingStage(),
                source.deliveries() == null ? null
                        : source.deliveries().stream().map(JdbcTrackingReadApi::delivery).toList());
    }

    private static StageTime stage(MessageTrackingService.StageTime source) {
        return new StageTime(source.stage(), source.startedAt(), source.finishedAt(), source.durationMs(),
                source.sincePreviousMs());
    }

    private static DeliveryTime delivery(MessageTrackingService.DeliveryTime source) {
        return new DeliveryTime(source.deliveryId(), source.targetId(), source.durationMs(), source.outcome(),
                source.state());
    }

    private static Failure failure(MessageTrackingService.Failure source) {
        return new Failure(source.trackingId(), source.deliveryId(), source.flowId(), source.targetId(),
                source.failingStage(), source.errorClass(), source.lastError(), source.origin(), source.failedAt());
    }
}
