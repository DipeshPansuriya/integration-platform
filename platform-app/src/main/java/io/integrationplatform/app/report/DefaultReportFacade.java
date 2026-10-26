package io.integrationplatform.app.report;

import io.integrationplatform.app.message.MessageTrackingService;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Default {@link ReportFacade} that aggregates the three read surfaces through a {@link ReportDataProvider}. */
public final class DefaultReportFacade implements ReportFacade {

    private final ReportDataProvider provider;

    public DefaultReportFacade(ReportDataProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public Report report(UUID trackingId, UUID flowId, LocalDate day) {
        MessageTrackingService.Timeline timeline = provider.loadTimeline(trackingId).orElse(null);
        List<MessageTrackingService.Failure> failures = provider.loadFailures(flowId, day);
        List<MessageTrackingService.StageTime> stageTimings = provider.loadStageTimings(trackingId);
        return new Report(trackingId, timeline,
                failures == null ? List.of() : List.copyOf(failures),
                stageTimings == null ? List.of() : List.copyOf(stageTimings));
    }
}