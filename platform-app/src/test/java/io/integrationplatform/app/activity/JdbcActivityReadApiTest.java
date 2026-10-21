package io.integrationplatform.app.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.audit.api.ActivityEvent;
import io.integrationplatform.audit.api.ActivityReadApi;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JdbcActivityReadApiTest {

    @Test
    void listMapsTheServicePageOntoTheContractPage() {
        ActivityService service = mock(ActivityService.class);
        JdbcActivityReadApi api = new JdbcActivityReadApi(service);
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 16);
        ActivityService.Activity source = new ActivityService.Activity("42", "admin-a", "CONFIG_CHANGED",
                List.of("flow:42"), "{\"v\":1}", "{\"v\":2}", "2026-10-16T10:00:00Z");
        ActivityService.ActivityPage page = new ActivityService.ActivityPage(List.of(source), 2, 25, false);
        when(service.list("CONFIG_CHANGED", from, to, 2, 25)).thenReturn(page);

        ActivityReadApi.ActivityPage result = api.list("CONFIG_CHANGED", from, to, 2, 25);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(25);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.items()).containsExactly(new ActivityEvent(42L, "admin-a", "CONFIG_CHANGED",
                List.of("flow:42"), "{\"v\":1}", "{\"v\":2}", "2026-10-16T10:00:00Z"));
    }

    @Test
    void eventMapsAVisibleEventAndKeepsForeignEventEmpty() {
        ActivityService service = mock(ActivityService.class);
        JdbcActivityReadApi api = new JdbcActivityReadApi(service);
        ActivityService.Activity source = new ActivityService.Activity("7", "admin-a", "CONFIG_CHANGED", null, null, null,
                "2026-10-15T10:00:00Z");
        when(service.event(7L)).thenReturn(Optional.of(source));
        when(service.event(99L)).thenReturn(Optional.empty());

        Optional<ActivityEvent> visible = api.event(7L);
        Optional<ActivityEvent> foreign = api.event(99L);

        assertThat(visible).contains(new ActivityEvent(7L, "admin-a", "CONFIG_CHANGED", null, null, null,
                "2026-10-15T10:00:00Z"));
        assertThat(foreign).isEmpty();
    }

    @Test
    void nullListsStayNull() {
        ActivityService service = mock(ActivityService.class);
        JdbcActivityReadApi api = new JdbcActivityReadApi(service);
        ActivityService.Activity source = new ActivityService.Activity("1", "actor", "ACTION", null, null, null, "2026-01-01T00:00:00Z");
        when(service.list(null, null, null, 0, 2)).thenReturn(
                new ActivityService.ActivityPage(List.of(source), 0, 2, false));

        ActivityReadApi.ActivityPage result = api.list(null, null, null, 0, 2);

        assertThat(result.items().get(0).targetIds()).isNull();
    }

    @Test
    void theTraceIdTravelsFromTheServiceOntoTheContractEvent() {
        ActivityService service = mock(ActivityService.class);
        JdbcActivityReadApi api = new JdbcActivityReadApi(service);
        when(service.event(5L)).thenReturn(Optional.of(new ActivityService.Activity("5", "admin-a", "CONFIG_CHANGED",
                List.of(), null, null, "2026-10-15T10:00:00Z", "trace-5")));

        assertThat(api.event(5L)).map(ActivityEvent::traceId).contains("trace-5");
    }
}
