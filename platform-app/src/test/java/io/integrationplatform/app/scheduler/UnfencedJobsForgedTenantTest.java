package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.integrationplatform.delivery.port.DeliveryDailyAggregates;
import io.integrationplatform.domain.scheduler.JobHandler;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Row 327 T158 (E14 T-144; ADR-012 :129): a job-definition row never holds a tenant id, so no job body may take one
 * from what the scheduler hands it. Covers the unfenced app-side job paths that exist today: the run wrapper every
 * job goes through, and the DAILY_REPORT body (T-138). CREDENTIAL_CLEANUP has its own test
 * ({@link CredentialCleanupHandlerTest}); DELIVERY_RETRY and PAYLOAD_PURGE bodies live in the fenced delivery lane.
 */
class UnfencedJobsForgedTenantTest {

    private static final UUID FORGED = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @Test
    void theRunWrapperHandsEveryJobEmptyJobDataNeverATenant() {
        List<String> received = new ArrayList<>();
        JobHandler recording = new JobHandler() {
            @Override
            public String type() {
                return SchedulerJobTasks.DAILY_REPORT;
            }

            @Override
            public void run(String jobData) {
                received.add(jobData);
            }
        };

        new JobRunWrapper(List.of(recording)).run(SchedulerJobTasks.DAILY_REPORT);

        assertThat(received).containsExactly("");
    }

    @Test
    void theDailyReportBodyIgnoresAForgedTenant() {
        // T-138 real body: the job data is ignored - the tenants come only from the active-tenant directory
        DailyReportPlaceholderHandler handler = new DailyReportPlaceholderHandler(List::of,
                new WorkerTenantBinding(), TransactionOperations.withoutTransaction(), new NoAggregates(),
                Clock.systemUTC());
        assertThatCode(() -> handler.run("{\"tenantId\":\"" + FORGED + "\"}")).doesNotThrowAnyException();
        assertThat(handler.type()).isEqualTo(SchedulerJobTasks.DAILY_REPORT);
    }

    private static final class NoAggregates implements DeliveryDailyAggregates {
        @Override
        public int refresh(LocalDate day) {
            throw new AssertionError("no tenant is active, so nothing is refreshed");
        }

        @Override
        public List<DailyCounts> aggregated(LocalDate day) {
            return List.of();
        }

        @Override
        public List<DailyCounts> direct(LocalDate day) {
            return List.of();
        }
    }
}
