package io.integrationplatform.app.report;

import io.integrationplatform.app.message.MessageTrackingService;
import java.util.List;

/**
 * E13 helper: extracts the UI-visible slices (stage timings and failed entries) from the raw report
 * payloads, so the controller/facade surface never hands the UI a tenant-bound tracking record directly.
 */
public final class FailedEntriesExtractor {

    private FailedEntriesExtractor() {
    }

    public static List<MessageTrackingService.StageTime> extractStageTimings(MessageTrackingService.Timeline timeline) {
        return uiStageTimings(timeline);
    }

    public static List<MessageTrackingService.StageTime> uiStageTimings(MessageTrackingService.Timeline timeline) {
        if (timeline == null || timeline.stages() == null) {
            return List.of();
        }
        return List.copyOf(timeline.stages());
    }

    public static List<MessageTrackingService.StageTime> toUiStageTimings(MessageTrackingService.Timeline timeline) {
        return uiStageTimings(timeline);
    }

    public static List<MessageTrackingService.Failure> extractFailedEntries(List<MessageTrackingService.Failure> failures) {
        return uiFailedEntries(failures);
    }

    public static List<MessageTrackingService.Failure> uiFailedEntries(List<MessageTrackingService.Failure> failures) {
        if (failures == null) {
            return List.of();
        }
        return List.copyOf(failures);
    }

    public static List<MessageTrackingService.Failure> toUiFailedEntries(List<MessageTrackingService.Failure> failures) {
        return uiFailedEntries(failures);
    }
}
