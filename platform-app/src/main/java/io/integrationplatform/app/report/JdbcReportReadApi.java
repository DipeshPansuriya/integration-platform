package io.integrationplatform.app.report;

import io.integrationplatform.app.message.MessageTrackingService;
import io.integrationplatform.tracking.api.DeliveryTime;
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
public final class JdbcReportReadApi implements ReportReadApi {

    private final MessageTrackingService service;
    private final ReportFacade facade;

    public JdbcReportReadApi(MessageTrackingService service) {
        this(unwrap(service), service);
    }

    public JdbcReportReadApi(ReportDataProvider provider) {
        this(new DefaultReportFacade(Objects.requireNonNull(provider, "provider")), null);
    }

    public JdbcReportReadApi(ReportFacade facade) {
        this(facade, null);
    }

    private JdbcReportReadApi(ReportFacade facade, MessageTrackingService service) {
        if (facade == null && service == null) {
            throw new IllegalArgumentException("facade or service required");
        }
        this.facade = facade;
        this.service = service;
    }

    private static ReportFacade unwrap(MessageTrackingService service) {
        Objects.requireNonNull(service, "service");
        return new DefaultReportFacade(new MessageTrackingReportDataProvider(service));
    }

    @Override
    public Report report(UUID trackingId, UUID flowId, LocalDate day) {
        ReportFacade.Report source = facade != null
                ? facade.report(trackingId, flowId, day)
                : new DefaultReportFacade(new MessageTrackingReportDataProvider(service)).report(trackingId, flowId, day);
        io.integrationplatform.app.message.MessageTrackingService.Timeline sourceTimeline = source.timeline();
        Timeline timeline = sourceTimeline == null ? null : timeline(sourceTimeline);
        List<Failure> failures = source.failures() == null ? List.of()
                : source.failures().stream().map(JdbcReportReadApi::failure).toList();
        List<StageTime> stageTimings = source.stageTimings() == null ? List.of()
                : source.stageTimings().stream().map(JdbcReportReadApi::stage).toList();
        return new Report(source.trackingId(), timeline, failures, stageTimings);
    }

    private static Timeline timeline(MessageTrackingService.Timeline source) {
        return new Timeline(source.trackingId(), source.flowId(), source.state(), source.acceptedAt(),
                source.stages() == null ? null : source.stages().stream().map(JdbcReportReadApi::stage).toList(),
                source.totalMs(), source.failingStage(),
                source.deliveries() == null ? null
                        : source.deliveries().stream().map(JdbcReportReadApi::delivery).toList());
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
