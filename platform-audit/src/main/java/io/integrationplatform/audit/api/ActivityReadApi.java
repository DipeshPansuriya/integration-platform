package io.integrationplatform.audit.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * E12 seam (arjun-fullstack): read-only activity tracking contract. Newest first, filtered by action and UTC day
 * range, one bounded page at a time, and one event by id. READS ONLY; never mutates an audit event (append-only).
 */
public interface ActivityReadApi {

    /** One page of activity events; {@code hasMore} when an older page exists. */
    record ActivityPage(List<ActivityEvent> items, int page, int size, boolean hasMore) {
    }

    /**
     * The bound tenant's events, newest first, narrowed by action and inclusive UTC days when given. {@code page}
     * and {@code size} may be {@code null}: an absent page is page 0 and an absent size is the reader's default
     * (50); the reader clamps a size to 1..200 and a negative page to 0 rather than refusing, so it always answers
     * a page.
     */
    ActivityPage list(String action, LocalDate from, LocalDate to, Integer page, Integer size);

    /** One of the bound tenant's events by id; empty when the tenant cannot see it. */
    Optional<ActivityEvent> event(long id);
}
