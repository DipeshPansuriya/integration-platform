package io.integrationplatform.audit.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E12 seam (arjun-fullstack): compile-time guard over the low-conflict contract surface. No implementation wired.
 */
class ActivityReadApiContractTest {

    @Test
    void contractTypesCompile() {
        ActivityEvent event = new ActivityEvent(1L, "actor", "ACTION", List.of("t"), null, null, "2026-01-01T00:00:00Z");
        assertNotNull(event);
        assertEquals(1L, event.id());
        assertTrue(new ActivityReadApi.ActivityPage(List.of(event), 0, 1, false).items().size() == 1);
        assertNotNull(LocalDate.now());
    }
}