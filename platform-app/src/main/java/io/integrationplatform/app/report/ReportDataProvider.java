package io.integrationplatform.app.report;

import io.integrationplatform.app.message.MessageTrackingService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * E13 (facade slice): the stub data provider contract the facade reads through. Implementations may be backed by the
 * message tracking store, a fake, or a stub; the facade itself never touches a store directly.
 */
public interface ReportDataProvider {

    Optional<MessageTrackingService.Timeline> loadTimeline(UUID trackingId);

    List<MessageTrackingService.Failure> loadFailures(UUID flowId, LocalDate day);

    List<MessageTrackingService.StageTime> loadStageTimings(UUID trackingId);
}