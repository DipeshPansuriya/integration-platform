package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.integrationplatform.domain.scheduler.JobHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Row 327 T158 (E14 T-144; ADR-012 :129): a job-definition row never holds a tenant id, so no job body may take one
 * from what the scheduler hands it. Covers the unfenced app-side job paths that exist today: the run wrapper every
 * job goes through, and the DAILY_REPORT placeholder (its real body is T-143). CREDENTIAL_CLEANUP has its own test
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
        // ponytail: the placeholder reads nothing; T-143's real body inherits this test and must keep it green
        assertThatCode(() -> new DailyReportPlaceholderHandler().run("{\"tenantId\":\"" + FORGED + "\"}"))
                .doesNotThrowAnyException();
        assertThat(new DailyReportPlaceholderHandler().type()).isEqualTo(SchedulerJobTasks.DAILY_REPORT);
    }
}
