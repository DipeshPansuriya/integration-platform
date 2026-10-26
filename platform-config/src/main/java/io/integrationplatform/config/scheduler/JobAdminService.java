package io.integrationplatform.config.scheduler;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.tenancy.port.SchedulerRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * E14 T-060 (ADR-012 section 5): the operator agent's job operations over the closed register - list, inspect, run
 * now, pause and resume. Every call writes one {@link JobAdminAudit} record; an unknown job type is a 404. Run now
 * moves the recurring task's pending execution to now; pause/resume change the register row and re-run the schedule
 * sync so the change takes effect at once. No tenant id anywhere (jobs are platform-wide, ADR-012 section 4).
 */
@Service
public class JobAdminService {

    /** How many recent runs an inspect returns, newest first. */
    public static final int RECENT_RUNS = 20;
    /** db-scheduler's instance id of every recurring task. */
    static final String RECURRING_INSTANCE = "recurring";

    private final JobDefinitionRepository definitions;
    private final JobRunSearch runs;
    private final SchedulerRegistry registry;
    private final SchedulerClient client;
    private final JobScheduleSync sync;
    private final JobAdminAudit audit;
    private final Clock clock;

    @Autowired
    public JobAdminService(JobDefinitionRepository definitions, JobRunSearch runs, SchedulerRegistry registry,
            SchedulerClient client, JobScheduleSync sync, JobAdminAudit audit) {
        this(definitions, runs, registry, client, sync, audit, Clock.systemUTC());
    }

    public JobAdminService(JobDefinitionRepository definitions, JobRunSearch runs, SchedulerRegistry registry,
            SchedulerClient client, JobScheduleSync sync, JobAdminAudit audit, Clock clock) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.client = Objects.requireNonNull(client, "client");
        this.sync = Objects.requireNonNull(sync, "sync");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<JobDefinitionRow> list(String agent) {
        List<JobDefinitionRow> rows = definitions.all();
        audit.record("LIST_JOBS", agent, null, "OK");
        return rows;
    }

    public Map<String, Object> inspect(String agent, String jobType) {
        JobDefinitionRow job = require("INSPECT_JOB", agent, jobType);
        Map<String, Object> view = Map.of("job", job,
                "recentRuns", runs.search(jobType, null, null, null, RECENT_RUNS));
        audit.record("INSPECT_JOB", agent, jobType, "OK");
        return view;
    }

    public void runNow(String agent, String jobType) {
        require("RUN_JOB_NOW", agent, jobType);
        if (!client.reschedule(TaskInstanceId.of(jobType, RECURRING_INSTANCE), clock.instant())) {
            audit.record("RUN_JOB_NOW", agent, jobType, "NO_PENDING_EXECUTION");
            throw ConfigApiException.conflict("the job has no pending execution to run now");
        }
        audit.record("RUN_JOB_NOW", agent, jobType, "OK");
    }

    public void pause(String agent, String jobType) {
        setPaused("PAUSE_JOB", agent, jobType, true);
    }

    public void resume(String agent, String jobType) {
        setPaused("RESUME_JOB", agent, jobType, false);
    }

    private void setPaused(String operation, String agent, String jobType, boolean paused) {
        Instant now = clock.instant();
        if (registry.setPaused(jobType, paused, agent, now) == 0) {
            audit.record(operation, agent, jobType, "NOT_FOUND");
            throw ConfigApiException.notFound();
        }
        sync.sync();
        audit.record(operation, agent, jobType, "OK");
    }

    private JobDefinitionRow require(String operation, String agent, String jobType) {
        return definitions.find(jobType).orElseThrow(() -> {
            audit.record(operation, agent, jobType, "NOT_FOUND");
            return ConfigApiException.notFound();
        });
    }
}
