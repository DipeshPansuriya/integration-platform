package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.delivery.port.DeliveryDailyAggregates;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** T-138 (E13-F1-S2): DAILY_REPORT refreshes yesterday's delivery pre-aggregate once per active tenant. */
class DailyReportHandlerTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID FORGED = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T00:30:00Z"), ZoneOffset.UTC);

    private final WorkerTenantBinding binding = new WorkerTenantBinding();
    private final List<String> refreshed = new ArrayList<>();

    @Test
    void eachActiveTenantRefreshesYesterdayWithThatTenantBound() {
        handler(List.of(A, B), null).run("{\"tenantId\":\"" + FORGED + "\"}");

        assertThat(refreshed).containsExactly(A + "@2026-10-18", B + "@2026-10-18");
    }

    @Test
    void oneTenantFailingDoesNotStopTheOthersButFailsTheRun() {
        assertThatThrownBy(() -> handler(List.of(A, B), A).run("")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 of 2");

        assertThat(refreshed).containsExactly(B + "@2026-10-18");
    }

    @Test
    void aThreadAlreadyBoundToATenantIsRefused() {
        binding.runAs(FORGED, () -> assertThatThrownBy(() -> handler(List.of(A), null).run(""))
                .isInstanceOf(IllegalStateException.class));

        assertThat(refreshed).isEmpty();
    }

    @Test
    void noBindingFailsLoudly() {
        DailyReportPlaceholderHandler handler = new DailyReportPlaceholderHandler(() -> List.of(A), null,
                TransactionOperations.withoutTransaction(), aggregates(null), CLOCK);

        assertThatThrownBy(() -> handler.run("")).isInstanceOf(IllegalStateException.class);
        assertThat(handler.type()).isEqualTo(SchedulerJobTasks.DAILY_REPORT);
    }

    private DailyReportPlaceholderHandler handler(List<UUID> tenants, UUID failing) {
        return new DailyReportPlaceholderHandler(() -> tenants, binding, TransactionOperations.withoutTransaction(),
                aggregates(failing), CLOCK);
    }

    private DeliveryDailyAggregates aggregates(UUID failing) {
        return new DeliveryDailyAggregates() {
            @Override
            public int refresh(LocalDate day) {
                UUID bound = binding.currentTenantId().orElseThrow();
                if (bound.equals(failing)) {
                    throw new IllegalStateException("boom");
                }
                refreshed.add(bound + "@" + day);
                return 1;
            }

            @Override
            public List<DailyCounts> aggregated(LocalDate day) {
                return List.of();
            }

            @Override
            public List<DailyCounts> direct(LocalDate day) {
                return List.of();
            }
        };
    }
}
