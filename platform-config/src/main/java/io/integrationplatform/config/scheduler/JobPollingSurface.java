package io.integrationplatform.config.scheduler;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import io.integrationplatform.domain.scheduler.PollingJobSurface;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * E14 runtime wiring (arjun-fullstack): the data-only polling surface an AI agent reads for job identity and
 * scheduling state. It is built on the closed register ({@link JobDefinitionRepository}), db-scheduler's pending
 * execution ({@link SchedulerClient}) and the latest recorded run ({@link JobRunSearch}). It carries no payload and
 * no tenant id (ADR-012 sections 4 and 5); scheduling decisions stay inside the scheduler.
 *
 * <p><b>"Changed since"</b> ({@link #pollSince}): a job counts when it ran since the cutoff (its {@code lastRunAt},
 * the latest run's finish or its start when it never finished, is at or after {@code since}) or when it was paused
 * since the cutoff (the register row's {@code paused_at}, reported as {@code pausedAt}, is at or after
 * {@code since}), or when its enabled / paused state changed since the cutoff (the register row's trigger-stamped
 * {@code state_changed_at}, reported as {@code stateChangedAt}) - which also covers a job that was only DISABLED or
 * only resumed, neither of which leaves a {@code paused_at}. The pending {@code nextRunAt} is the schedule, not a
 * change.
 *
 * <p>The latest runs of every job are read in one batch (newest first, {@link JobRunSearch#MAX_LIMIT} rows) instead
 * of one query per job; only when that batch is full - so a quiet job's latest run may lie beyond it - does a job
 * missing from it get its own lookup. Failures of a single job's lookup are logged at WARN with the job id and the
 * job is still listed with the unknown field null.
 */
@Service
public class JobPollingSurface implements PollingJobSurface {

    private static final Logger LOG = LoggerFactory.getLogger(JobPollingSurface.class);

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
        List<JobDefinitionRow> rows = definitions.all();
        LastRuns lastRuns = latestRuns();
        return rows.stream().map(row -> toSummary(row, lastRuns.of(row.jobType()))).toList();
    }

    /** Ran, paused or state-changed since the cutoff (see the class note); the pending execution is only resolved for jobs that qualify. */
    @Override
    public List<JobSummary> pollSince(Instant since) {
        if (since == null) {
            return poll();
        }
        List<JobDefinitionRow> rows = definitions.all();
        LastRuns lastRuns = latestRuns();
        return rows.stream()
                .map(row -> new Candidate(row, lastRuns.of(row.jobType())))
                .filter(candidate -> changedSince(candidate, since))
                .map(candidate -> toSummary(candidate.row(), candidate.lastRunAt()))
                .toList();
    }

    private record Candidate(JobDefinitionRow row, Instant lastRunAt) {
    }

    private static boolean changedSince(Candidate candidate, Instant since) {
        Instant pausedAt = candidate.row().pausedAt();
        boolean ran = candidate.lastRunAt() != null && !candidate.lastRunAt().isBefore(since);
        Instant stateChangedAt = candidate.row().stateChangedAt();
        boolean paused = pausedAt != null && !pausedAt.isBefore(since);
        boolean stateChanged = stateChangedAt != null && !stateChangedAt.isBefore(since);
        return ran || paused || stateChanged;
    }

    private JobSummary toSummary(JobDefinitionRow row, Instant lastRunAt) {
        return new JobSummary(row.jobType(), row.jobType(), state(row), pendingExecutionTime(row.jobType()), lastRunAt,
                row.pausedAt(), row.stateChangedAt());
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
            LOG.warn("Polling surface: pending execution lookup failed for job {}; nextRunAt reported as unknown",
                    jobType, e);
            return null;
        }
    }

    /** The newest run of every job in one query; falls back to a per-job lookup only where the batch cannot answer. */
    private LastRuns latestRuns() {
        Map<String, Instant> latest = new HashMap<>();
        boolean complete = true;
        try {
            List<JobRunView> batch = runs.search(null, null, null, null, JobRunSearch.MAX_LIMIT);
            for (JobRunView run : batch) {
                if (!latest.containsKey(run.jobType())) {
                    latest.put(run.jobType(), finishedOrStarted(run)); // newest first: the first row is the latest
                }
            }
            complete = batch.size() < JobRunSearch.MAX_LIMIT;
        } catch (RuntimeException e) {
            LOG.warn("Polling surface: batch run lookup failed; falling back to one lookup per job", e);
            complete = false;
        }
        return new LastRuns(latest, complete);
    }

    private static Instant finishedOrStarted(JobRunView run) {
        return run.finished() != null ? run.finished() : run.started();
    }

    /** Latest-run times by job type; a job absent from an incomplete batch is looked up on its own. */
    private final class LastRuns {
        private final Map<String, Instant> latest;
        private final boolean complete;

        LastRuns(Map<String, Instant> latest, boolean complete) {
            this.latest = latest;
            this.complete = complete;
        }

        Instant of(String jobType) {
            if (latest.containsKey(jobType)) {
                return latest.get(jobType);
            }
            return complete ? null : lookUp(jobType);
        }

        private Instant lookUp(String jobType) {
            try {
                List<JobRunView> recent = runs.search(jobType, null, null, null, 1);
                return recent.isEmpty() ? null : finishedOrStarted(recent.get(0));
            } catch (RuntimeException e) {
                LOG.warn("Polling surface: latest run lookup failed for job {}; lastRunAt reported as unknown",
                        jobType, e);
                return null;
            }
        }
    }
}
