package io.integrationplatform.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.domain.audit.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * E12 write side: {@link ActivityEntry} is the tenant-less write shape of the activity view (the tenant is only ever
 * the bound one, R08) and maps from the domain {@link AuditEvent} the config edits already publish.
 */
class ActivityEntryTest {

    private static final Instant AT = Instant.parse("2026-10-21T08:00:00Z");

    @Test
    void anAuditEventMapsToAnEntryKeepingWhoWhatBeforeAfterWhenAndTheTrace() {
        AuditEvent event = new AuditEvent(UUID.randomUUID(), "admin-a", "CONFIG_CHANGED", List.of("flow:1"),
                "{\"v\":1}", "{\"v\":2}", AT);

        ActivityEntry entry = ActivityEntry.from(event, "trace-123");

        assertThat(entry).isEqualTo(new ActivityEntry("admin-a", "CONFIG_CHANGED", List.of("flow:1"), "{\"v\":1}",
                "{\"v\":2}", AT, "trace-123"));
    }

    @Test
    void missingTargetIdsBecomeEmptyAndTheTraceMayBeAbsent() {
        ActivityEntry entry = new ActivityEntry("admin-a", "CONFIG_CHANGED", null, null, null, AT, null);

        assertThat(entry.targetIds()).isEmpty();
        assertThat(entry.traceId()).isNull();
    }

    @Test
    void anEntryWithoutActorActionOrTimeIsRefused() {
        assertThatThrownBy(() -> new ActivityEntry(" ", "A", List.of(), null, null, AT, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActivityEntry("a", null, List.of(), null, null, AT, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActivityEntry("a", "A", List.of(), null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theActivityEventCarriesTheTraceIdAndTheOldShapeHasNone() {
        assertThat(new ActivityEvent(1L, "a", "A", List.of(), null, null, "2026-10-21T08:00:00Z", "t-1").traceId())
                .isEqualTo("t-1");
        assertThat(new ActivityEvent(1L, "a", "A", List.of(), null, null, "2026-10-21T08:00:00Z").traceId()).isNull();
    }
}
