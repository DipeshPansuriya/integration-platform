package io.integrationplatform.app.report;

import io.integrationplatform.app.message.MessageTrackingService;
import io.integrationplatform.app.message.TrackingContractMapper;
import io.integrationplatform.tracking.api.Failure;
import io.integrationplatform.tracking.api.Report;
import io.integrationplatform.tracking.api.ReportReadApi;
import io.integrationplatform.tracking.api.StageTime;
import io.integrationplatform.tracking.api.Timeline;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Behavior-backed runtime slice (E13): the {@link ReportReadApi} contract backed by the existing
 * report façade / tracking service, so the low-conflict {@code platform-tracking} report contract is
 * exercised against the real (tenant-scoped) behavior instead of being a compile-time guard only.
 */
public final class ReportReadApiAdapter implements ReportReadApi {

    private final ReportFacade facade;

    public ReportReadApiAdapter(ReportFacade facade) {
        this.facade = Objects.requireNonNull(facade, "facade");
    }

    @Override
    public Report report(UUID trackingId, UUID flowId, LocalDate day) {
        ReportFacade.Report source = facade.report(trackingId, flowId, day);
        MessageTrackingService.Timeline sourceTimeline = source.timeline();
        Timeline timeline = sourceTimeline == null ? null : TrackingContractMapper.timeline(sourceTimeline);
        List<Failure> failures = source.failures() == null ? List.of()
                : source.failures().stream().map(TrackingContractMapper::failure).toList();
        List<StageTime> stageTimings = source.stageTimings() == null ? List.of()
                : source.stageTimings().stream().map(TrackingContractMapper::stage).toList();
        return new Report(source.trackingId(), timeline, failures, stageTimings);
    }
}
