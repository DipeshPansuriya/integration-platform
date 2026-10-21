package io.integrationplatform.app.report;

import io.integrationplatform.app.message.MessageTrackingService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * E13 default stub: an empty {@link ReportDataProvider} used when no message tracking service is available, so the
 * report façade application context can start and resolve the endpoint (minimal startup verification).
 */
public final class StubReportDataProvider implements ReportDataProvider {

    @Override
    public Optional<MessageTrackingService.Timeline> loadTimeline(UUID trackingId) {
        return Optional.empty();
    }

    @Override
    public List<MessageTrackingService.Failure> loadFailures(UUID flowId, LocalDate day) {
        return List.of();
    }

    @Override
    public List<MessageTrackingService.StageTime> loadStageTimings(UUID trackingId) {
        return List.of();
    }
}
