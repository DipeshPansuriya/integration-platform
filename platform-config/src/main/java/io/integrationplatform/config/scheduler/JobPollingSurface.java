package io.integrationplatform.config.scheduler;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import io.integrationplatform.domain.scheduler.PollingJobSurface;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * E14 runtime wiring (arjun-fullstack): the data-only polling surface an AI agent reads for job identity and
 * scheduling state. It is built on the closed register ({@link JobDefinitionRepository}), db-scheduler's pending
 * execution ({@link SchedulerClient}) and the latest recorded run ({@link JobRunSearch}). It carries no payload and
 * no tenant id (ADR-012 sections 4 and 5); scheduling decisions stay inside the scheduler.
 */
@Service
public class JobPollingSurface implements PollingJobSurface {

    /** db-scheduler's instance id of every recurring task (mirrors {@link JobAdminService}). */
    static final String RECURRING_INSTANCE = "recurring";

    private final JobDefinitionRepository definitions;
    private final JobRunSearch runs;
    private final SchedulerClient client;

    @Autowired
    public JobPollingSurface(JobDefinitionRepository definitions, JobRunSearch runs, SchedulerClient client) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public List<JobSummary> poll() {
        List<JobSummary> out = new ArrayList<>();
        for (JobDefinitionRow row : definitions.all()) {
            out.add(toSummary(row));
        }
        return List.copyOf(out);
    }

    private JobSummary toSummary(JobDefinitionRow row) {
        Instant nextRunAt = pendingExecutionTime(row.jobType());
        Instant lastRunAt = latestRunFinished(row.jobType());
        return new JobSummary(row.jobType(), row.jobType(), state(row), nextRunAt, lastRunAt);
    }

    private String state(JobDefinitionRow row) {
        if (row.paused()) {
            return "PAUSED";
        }
        if (!row.enabled()) {
            return "DISABLED";
        }
        return "ACTIVE";
    }

    private Instant pendingExecutionTime(String jobType) {
        try {
            Optional<ScheduledExecution<Object>> execution =
                    client.getScheduledExecution(TaskInstanceId.of(jobType, RECURRING_INSTANCE));
            return execution.map(ScheduledExecution::getExecutionTime).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Instant latestRunFinished(String jobType) {
        List<JobRunView> recent = runs.search(jobType, null, null, null, 1);
        if (recent.isEmpty()) {
            return null;
        }
        JobRunView run = recent.get(0);
        return run.finished() != null ? run.finished() : run.started();
    }
}
