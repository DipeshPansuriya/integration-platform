package io.integrationplatform.tracking.api;

import java.util.List;
import java.util.UUID;

/**
 * E13 seam (arjun-fullstack): one aggregated report slice for one message / flow / day, behind a single read face.
 * Plain data; populated by a {@link ReportReadApi} implementation later.
 */
public record Report(UUID trackingId, Timeline timeline, List<Failure> failures, List<StageTime> stageTimings) {
}