package io.integrationplatform.tracking.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * E13 seam (arjun-fullstack): read-only report contract - one aggregated report slice for one message / flow / day,
 * populated through tracking data only. READS ONLY.
 */
public interface ReportReadApi {

    /** Aggregated report slice; implementations return an empty/absent result rather than throwing for missing rows. */
    Report report(UUID trackingId, UUID flowId, LocalDate day);
}