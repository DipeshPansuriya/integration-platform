package io.integrationplatform.tracking.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * E13 seam (arjun-fullstack): compile-time guard over the low-conflict contract surface. No implementation wired.
 */
class TrackingReportContractTest {

    @Test
    void contractTypesCompile() {
        StageTime stage = new StageTime("ROUTE", "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z", 1000L, 0L);
        DeliveryTime delivery = new DeliveryTime("d", "t", 50L, "OK", io.integrationplatform.domain.delivery.DeliveryState.DELIVERED);
        Timeline timeline = new Timeline("tid", "fid", "DELIVERED", "2026-01-01T00:00:00Z",
                List.of(stage), 1000L, null, List.of(delivery));
        Failure failure = new Failure("tid", "did", "fid", "tgt", "DELIVER", "X", "err", "PLATFORM", "2026-01-01T00:00:00Z");
        Report report = new Report(UUID.randomUUID(), timeline, List.of(failure), List.of(stage));
        assertNotNull(timeline);
        assertNotNull(report);
        assertEquals(1, new Timeline("tid", "fid", "DELIVERED", null, List.of(stage), null, null, List.of()).stages().size());
        assertNotNull(LocalDate.now());
    }
}
