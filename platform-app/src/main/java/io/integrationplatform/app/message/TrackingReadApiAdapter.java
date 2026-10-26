package io.integrationplatform.app.message;

import io.integrationplatform.tracking.api.Failure;
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
 * {@code ActivityReadApiAdapter}; the record-to-contract mapping lives in {@link TrackingContractMapper}.
 */
public final class TrackingReadApiAdapter implements TrackingReadApi {

    private final MessageTrackingService service;

    public TrackingReadApiAdapter(MessageTrackingService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public Optional<Timeline> timeline(UUID trackingId) {
        return service.timeline(trackingId).map(TrackingContractMapper::timeline);
    }

    @Override
    public List<Failure> failures(UUID flowId, LocalDate day) {
        List<MessageTrackingService.Failure> source = service.failures(flowId, day);
        if (source == null) {
            return List.of();
        }
        return source.stream().map(TrackingContractMapper::failure).toList();
    }
}
