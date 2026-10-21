package io.integrationplatform.tracking.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * E13 seam (arjun-fullstack): read-only tracking contract. Timeline by tracking id, failures per flow and UTC day.
 * READS ONLY. Implementations land later in platform-app; this fixes the shape. No coupling to E9/E10 runtime.
 */
public interface TrackingReadApi {

    /** One message's journey by tracking id; empty when the reader cannot see it. */
    Optional<Timeline> timeline(UUID trackingId);

    /** Failed or dead-lettered messages for one flow and UTC day, newest first; null filters are allowed. */
    List<Failure> failures(UUID flowId, LocalDate day);
}