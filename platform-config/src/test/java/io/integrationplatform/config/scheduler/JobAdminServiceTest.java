package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.tenancy.port.SchedulerRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * E14 T-060 (ADR-012 section 5): the agent admin operations over the closed register - list, inspect, run now,
 * pause (and resume). Every operation writes one audit record naming the agent; an unknown job type is a 404.
 *
 * <pre>
 * Scenario: The operator agent runs, pauses and inspects a job, and each call is audited
 *   Given the closed register with DAILY_REPORT
 *   When the operator agent lists, inspects, runs now or pauses DAILY_REPORT
 *   Then the call takes effect and one audit record names the agent, the operation, the job and the outcome
 * </pre>
 */
class JobAdminServiceTest {

    static final Instant NOW = Instant.parse("2026-10-19T09:00:00Z");
    static final JobDefinitionRow DAILY = new JobDefinitionRow("DAILY_REPORT", "CRON", "0 5 0 * * *", true, false,
            600, 300, "{}");

    private final JobDefinitionRepository definitions = mock(JobDefinitionRepository.class);
    private final JobRunSearch runs = mock(JobRunSearch.class);
    private final SchedulerRegistry registry = mock(SchedulerRegistry.class);
    private final SchedulerClient client = mock(SchedulerClient.class);
    private final JobScheduleSync sync = mock(JobScheduleSync.class);
    private final JobAdminAudit audit = mock(JobAdminAudit.class);
    private final JobAdminService service = new JobAdminService(definitions, runs, registry, client, sync, audit,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void listReturnsTheRegisterAndAudits() {
        when(definitions.all()).thenReturn(List.of(DAILY));

        assertThat(service.list("ops-agent")).containsExactly(DAILY);
        verify(audit).record("LIST_JOBS", "ops-agent", null, "OK");
    }

    @Test
    void inspectReturnsTheRowAndRecentRunsAndAudits() {
        when(definitions.find("DAILY_REPORT")).thenReturn(Optional.of(DAILY));
        when(runs.search("DAILY_REPORT", null, null, null, JobAdminService.RECENT_RUNS)).thenReturn(List.of());

        Map<String, Object> view = service.inspect("ops-agent", "DAILY_REPORT");

        assertThat(view).containsEntry("job", DAILY).containsEntry("recentRuns", List.of());
        verify(audit).record("INSPECT_JOB", "ops-agent", "DAILY_REPORT", "OK");
    }

    @Test
    void runNowReschedulesThePendingExecutionToNowAndAudits() {
        when(definitions.find("DAILY_REPORT")).thenReturn(Optional.of(DAILY));
        when(client.reschedule(any(TaskInstanceId.class), eq(NOW))).thenReturn(true);

        service.runNow("ops-agent", "DAILY_REPORT");

        verify(client).reschedule(TaskInstanceId.of("DAILY_REPORT", "recurring"), NOW);
        verify(audit).record("RUN_JOB_NOW", "ops-agent", "DAILY_REPORT", "OK");
    }

    @Test
    void runNowWithNoPendingExecutionIsAConflictAndAudited() {
        when(definitions.find("DAILY_REPORT")).thenReturn(Optional.of(DAILY));
        when(client.reschedule(any(TaskInstanceId.class), eq(NOW))).thenReturn(false);

        assertThatThrownBy(() -> service.runNow("ops-agent", "DAILY_REPORT"))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(409));
        verify(audit).record("RUN_JOB_NOW", "ops-agent", "DAILY_REPORT", "NO_PENDING_EXECUTION");
    }

    @Test
    void pauseRecordsWhoAndWhenSyncsAndAudits() {
        when(registry.setPaused("DAILY_REPORT", true, "ops-agent", NOW)).thenReturn(1);

        service.pause("ops-agent", "DAILY_REPORT");

        verify(registry).setPaused("DAILY_REPORT", true, "ops-agent", NOW);
        verify(sync).sync();
        verify(audit).record("PAUSE_JOB", "ops-agent", "DAILY_REPORT", "OK");
    }

    @Test
    void resumeClearsTheMarkSyncsAndAudits() {
        when(registry.setPaused("DAILY_REPORT", false, "ops-agent", NOW)).thenReturn(1);

        service.resume("ops-agent", "DAILY_REPORT");

        verify(sync).sync();
        verify(audit).record("RESUME_JOB", "ops-agent", "DAILY_REPORT", "OK");
    }

    @Test
    void anUnknownJobIsNotFoundEverywhereAndAudited() {
        when(definitions.find("NOPE")).thenReturn(Optional.empty());
        when(registry.setPaused(eq("NOPE"), eq(true), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.inspect("ops-agent", "NOPE"))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.runNow("ops-agent", "NOPE"))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.pause("ops-agent", "NOPE"))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        verify(audit).record("INSPECT_JOB", "ops-agent", "NOPE", "NOT_FOUND");
        verify(audit).record("RUN_JOB_NOW", "ops-agent", "NOPE", "NOT_FOUND");
        verify(audit).record("PAUSE_JOB", "ops-agent", "NOPE", "NOT_FOUND");
        verify(client, never()).reschedule(any(TaskInstanceId.class), any(Instant.class));
        verify(sync, never()).sync();
    }
}
