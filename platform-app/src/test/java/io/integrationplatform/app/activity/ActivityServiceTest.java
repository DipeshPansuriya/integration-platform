package io.integrationplatform.app.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * Row327 T118 (E12-F2-S2, T-069): the activity view's rules over the tenant-scoped reader, without Docker - the
 * store is a mock (its SQL and the RLS gate are proven by {@code ActivityContainerTest}). Newest first; the date
 * range is UTC days, inclusive, bound half-open; the page size is bounded (default 50, at most 200, at least 1);
 * one row beyond the page tells the caller there is more. Not tagged container (R03).
 */
class ActivityServiceTest {

    private final JdbcActivityStore store = mock(JdbcActivityStore.class);
    private final ActivityService service = new ActivityService(store);

    private static JdbcActivityStore.ActivityRow row(long id, String at) {
        return new JdbcActivityStore.ActivityRow(id, "admin-a", "CONFIG_CHANGED", List.of("flow:" + id), "{\"v\":1}",
                "{\"v\":2}", Instant.parse(at));
    }

    @Test
    void theDateRangeIsInclusiveUtcDaysBoundHalfOpenAndTheActionPassesThrough() {
        when(store.page(any(), any(), any(), anyInt(), anyLong())).thenReturn(List.of());

        service.list("CONFIG_CHANGED", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16), null, null);

        verify(store).page("CONFIG_CHANGED", Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-17T00:00:00Z"), ActivityService.DEFAULT_SIZE + 1, 0L);
    }

    @Test
    void noFilterMeansNoActionAndNoDateBound() {
        when(store.page(any(), any(), any(), anyInt(), anyLong())).thenReturn(List.of());

        service.list(" ", null, null, null, null);

        verify(store).page(isNull(), isNull(), isNull(), eq(ActivityService.DEFAULT_SIZE + 1), eq(0L));
    }

    @Test
    void thePageSizeIsBoundedAndThePageBecomesTheOffset() {
        when(store.page(any(), any(), any(), anyInt(), anyLong())).thenReturn(List.of());

        ActivityService.ActivityPage huge = service.list(null, null, null, 3, 100_000);
        ActivityService.ActivityPage tiny = service.list(null, null, null, -4, 0);

        assertThat(huge.size()).isEqualTo(ActivityService.MAX_SIZE);
        assertThat(huge.page()).isEqualTo(3);
        verify(store).page(null, null, null, ActivityService.MAX_SIZE + 1, 3L * ActivityService.MAX_SIZE);
        assertThat(tiny.size()).isEqualTo(1);
        assertThat(tiny.page()).isZero();
        verify(store).page(null, null, null, 2, 0L);
    }

    @Test
    void thePageIsClampedSoOffsetPlusSizeNeverPassesTheCeiling() {
        when(store.page(any(), any(), any(), anyInt(), anyLong())).thenReturn(List.of());

        ActivityService.ActivityPage maxSize = service.list(null, null, null, Integer.MAX_VALUE, 200);
        ActivityService.ActivityPage defaultSize = service.list(null, null, null, Integer.MAX_VALUE, null);

        assertThat(ActivityService.MAX_OFFSET).isEqualTo(10_000);
        assertThat(maxSize.page()).isEqualTo(49);
        verify(store).page(null, null, null, 201, 9_800L);
        assertThat(defaultSize.page()).isEqualTo(199);
        verify(store).page(null, null, null, ActivityService.DEFAULT_SIZE + 1, 9_950L);
    }

    @Test
    void theLastReachablePageNeverSaysThereIsMore() {
        List<JdbcActivityStore.ActivityRow> rows = LongStream.rangeClosed(1, 201)
                .mapToObj(id -> row(id, "2026-10-11T10:00:00Z")).toList();
        when(store.page(null, null, null, 201, 9_800L)).thenReturn(rows);

        ActivityService.ActivityPage last = service.list(null, null, null, 49, 200);

        assertThat(last.items()).hasSize(200);
        assertThat(last.hasMore()).as("the next page would clamp back to this one").isFalse();
    }

    @Test
    void eventsAreNewestFirstAndOneRowBeyondThePageMeansMore() {
        List<JdbcActivityStore.ActivityRow> rows = LongStream.rangeClosed(1, 3)
                .mapToObj(id -> row(id, "2026-10-1" + id + "T10:00:00Z")).toList();
        when(store.page(null, null, null, 3, 0L)).thenReturn(rows);

        ActivityService.ActivityPage page = service.list(null, null, null, 0, 2);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).extracting(ActivityService.Activity::id).containsExactly("3", "2");
        assertThat(page.items().get(0)).isEqualTo(new ActivityService.Activity("3", "admin-a", "CONFIG_CHANGED",
                List.of("flow:3"), "{\"v\":1}", "{\"v\":2}", "2026-10-13T10:00:00Z"));
    }

    @Test
    void aShortPageHasNoMore() {
        when(store.page(null, null, null, 3, 0L)).thenReturn(List.of(row(1, "2026-10-11T10:00:00Z")));

        assertThat(service.list(null, null, null, 0, 2).hasMore()).isFalse();
    }

    @Test
    void oneEventIsTheBoundTenantsOrNothing() {
        when(store.event(5L)).thenReturn(Optional.of(row(5, "2026-10-15T10:00:00Z")));
        when(store.event(6L)).thenReturn(Optional.empty());

        assertThat(service.event(5L)).map(ActivityService.Activity::id).contains("5");
        assertThat(service.event(6L)).isEmpty();
    }
}
