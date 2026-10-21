package io.integrationplatform.app.activity;

import io.integrationplatform.audit.api.ActivityEvent;
import io.integrationplatform.audit.api.ActivityReadApi;
import java.util.List;
import java.util.Objects;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Behavior-backed runtime slice (E12-F2-S2, T-069): the {@link ActivityReadApi} contract backed by the tenant
 * activity view's service layer, so the low-conflict {@code platform-audit} contract is exercised against the real
 * (tenant-scoped, bounded, newest-first) behavior instead of being a compile-time guard only.
 *
 * <p>This adapter is the one place that crosses from the shared contract module into {@code platform-app}; it
 * delegates all reading, filtering, clamping and tenant scoping to {@link ActivityService} and does no reading of
 * its own.
 */
public final class JdbcActivityReadApi implements ActivityReadApi {

    private final ActivityService service;

    public JdbcActivityReadApi(ActivityService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public ActivityPage list(String action, LocalDate from, LocalDate to, Integer page, Integer size) {
        ActivityService.ActivityPage source = service.list(action, from, to, page, size);
        List<ActivityEvent> items = source.items().stream()
                .map(JdbcActivityReadApi::event)
                .toList();
        return new ActivityPage(items, source.page(), source.size(), source.hasMore());
    }

    @Override
    public Optional<ActivityEvent> event(long id) {
        return service.event(id).map(JdbcActivityReadApi::event);
    }

    private static ActivityEvent event(ActivityService.Activity value) {
        // the service keeps the id as text; the contract carries it as the long it is in the ledger
        long id = Long.parseLong(value.id());
        List<String> targetIds = value.targetIds() == null ? null : List.copyOf(value.targetIds());
        return new ActivityEvent(id, value.actor(), value.action(), targetIds, value.before(), value.after(),
                value.at(), value.traceId());
    }
}