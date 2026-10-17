package io.integrationplatform.app.activity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Row327 T118 (E12-F2-S2, T-069): the tenant's activity view - its own audit events, read LIVE from
 * {@link JdbcActivityStore} inside the caller's tenant-stamped read transaction, so a tenant only ever sees its own
 * rows (RLS + the base's own predicate).
 *
 * <ul>
 *   <li>Newest first (event time, then id).</li>
 *   <li>Filters: one action (blank means none) and a date range of inclusive UTC days, bound half-open
 *       ({@code [from 00:00Z, to+1 00:00Z)}).</li>
 *   <li>The page size is bounded: {@value #DEFAULT_SIZE} by default, at most {@value #MAX_SIZE}, at least 1; a
 *       negative page is page 0. Out-of-range values are clamped, not refused - the view always answers a page.</li>
 *   <li>One row beyond the page is read, so the answer says whether there is more without a count query.</li>
 * </ul>
 */
public class ActivityService {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;

    /** One audit event as the activity view shows it; {@code at} is the event time as a UTC instant. */
    public record Activity(String id, String actor, String action, List<String> targetIds, String before,
            String after, String at) {
    }

    /** One page of the activity view; {@code hasMore} when a later page holds older events. */
    public record ActivityPage(List<Activity> items, int page, int size, boolean hasMore) {
    }

    private final JdbcActivityStore store;

    public ActivityService(JdbcActivityStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The bound tenant's events, newest first, narrowed by action and inclusive UTC dates when given. */
    @Transactional(readOnly = true)
    public ActivityPage list(String action, LocalDate from, LocalDate to, Integer page, Integer size) {
        int boundedSize = size == null ? DEFAULT_SIZE : Math.clamp(size, 1, MAX_SIZE);
        int boundedPage = page == null ? 0 : Math.max(page, 0);
        List<JdbcActivityStore.ActivityRow> rows = store.page(
                action == null || action.isBlank() ? null : action,
                from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                boundedSize + 1, (long) boundedPage * boundedSize);
        List<Activity> items = rows.stream()
                .sorted(Comparator.comparing(JdbcActivityStore.ActivityRow::at)
                        .thenComparingLong(JdbcActivityStore.ActivityRow::id).reversed())
                .limit(boundedSize)
                .map(ActivityService::activity)
                .toList();
        return new ActivityPage(items, boundedPage, boundedSize, rows.size() > boundedSize);
    }

    /** One of the bound tenant's events by id; empty when the tenant cannot see it. */
    @Transactional(readOnly = true)
    public Optional<Activity> event(long id) {
        return store.event(id).map(ActivityService::activity);
    }

    private static Activity activity(JdbcActivityStore.ActivityRow row) {
        return new Activity(Long.toString(row.id()), row.actor(), row.action(), row.targetIds(), row.before(),
                row.after(), row.at().toString());
    }
}
