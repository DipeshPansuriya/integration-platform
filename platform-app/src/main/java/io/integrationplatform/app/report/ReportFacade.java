package io.integrationplatform.app.report;

import io.integrationplatform.app.message.MessageTrackingService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * E13 (facade slice): one read-side entry point that aggregates a message's timeline, its failures and its stage
 * timings behind a single interface, so the controller adapter and (later) the ledger stay thin. Standalone: no
 * dependency on E12 seeding; the data is supplied by a {@link ReportDataProvider}.
 */
public interface ReportFacade {

    /** One aggregated report slice for one message / flow / day. */
    record Report(UUID trackingId,
            MessageTrackingService.Timeline timeline,
            List<MessageTrackingService.Failure> failures,
            List<MessageTrackingService.StageTime> stageTimings) {
    }

    Report report(UUID trackingId, UUID flowId, LocalDate day);
}