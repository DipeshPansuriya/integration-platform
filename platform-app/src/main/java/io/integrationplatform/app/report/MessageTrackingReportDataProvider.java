package io.integrationplatform.app.report;

import io.integrationplatform.app.message.MessageTrackingService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * E13 (facade slice): read adapter that fills {@link ReportDataProvider} from the real
 * {@link MessageTrackingService}. No service redesign - only translates its read calls. A missing
 * message surfaces as an empty {@link Optional} (absent row); a read fault on the timeline propagates.
 */
public final class MessageTrackingReportDataProvider implements ReportDataProvider {

    private final MessageTrackingService service;

    public MessageTrackingReportDataProvider(MessageTrackingService service) {
        this.service = java.util.Objects.requireNonNull(service, "service");
    }

    @Override
    public Optional<MessageTrackingService.Timeline> loadTimeline(UUID trackingId) {
        if (trackingId == null) {
            return Optional.empty();
        }
        // not-found is an empty Optional from the service; any exception is a real fault and must surface (a 500, never a
        // false "no timeline" that the facade controller would record as a forged-tenant denial)
        return service.timeline(trackingId);
    }

    @Override
    public List<MessageTrackingService.Failure> loadFailures(UUID flowId, LocalDate day) {
        try {
            List<MessageTrackingService.Failure> failures = service.failures(flowId, day);
            return failures == null ? List.of() : List.copyOf(failures);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    @Override
    public List<MessageTrackingService.StageTime> loadStageTimings(UUID trackingId) {
        if (trackingId == null) {
            return List.of();
        }
        // no dedicated stage-timing read exists; derive from the timeline's recorded stages
        return loadTimeline(trackingId)
                .map(MessageTrackingService.Timeline::stages)
                .map(list -> list == null ? List.<MessageTrackingService.StageTime>of()
                        : List.copyOf(list))
                .orElse(List.of());
    }
}