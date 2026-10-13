package io.integrationplatform.app.scheduler;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import io.integrationplatform.config.scheduler.JobDefinitionRepository;
import io.integrationplatform.config.scheduler.JobDefinitionRow;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E14 T-056 (ADR-012 Decision 2): the {@code job_definition} row IS the schedule. The row is read fresh on every
 * computation, so an operator's row change (delay, pattern, pause, resume) takes effect at the next schedule
 * computation or sync without any restart; db-scheduler's own startup reconcile cancels paused rows and reschedules
 * changed ones. A cron pattern is resolved in UTC - the register's cadences are UTC timing (V2_2_1420).
 */
public final class JobDefinitionSchedule implements Schedule {

    private final String jobType;
    private final JobDefinitionRepository repository;
    /** db-scheduler's CronSchedule is immutable; cached per pattern so a changed row simply gets a new entry. */
    private final Map<String, CronSchedule> cronByPattern = new ConcurrentHashMap<>();

    public JobDefinitionSchedule(String jobType, JobDefinitionRepository repository) {
        this.jobType = jobType;
        this.repository = repository;
    }

    private Optional<JobDefinitionRow> row() {
        return repository.find(jobType).filter(JobDefinitionRow::active);
    }

    @Override
    public Instant getNextExecutionTime(ExecutionComplete executionComplete) {
        Optional<JobDefinitionRow> row = row();
        if (row.isEmpty()) {
            return Schedule.NEVER;
        }
        return switch (row.get().scheduleKind()) {
            case "FIXED_DELAY" -> executionComplete.getTimeDone()
                    .plusSeconds(Long.parseLong(row.get().schedule()));
            case "CRON" -> cronSchedule(row.get().schedule()).getNextExecutionTime(executionComplete);
            default -> Schedule.NEVER;
        };
    }

    @Override
    public Instant getInitialExecutionTime(Instant now) {
        Optional<JobDefinitionRow> row = row();
        if (row.isEmpty()) {
            return Schedule.NEVER;
        }
        return switch (row.get().scheduleKind()) {
            case "FIXED_DELAY" -> now; // upstream FixedDelay: the first fire is immediately after the sync
            case "CRON" -> cronSchedule(row.get().schedule()).getInitialExecutionTime(now);
            default -> Schedule.NEVER;
        };
    }

    /** A missing, paused or disabled row disables the task: the startup reconcile cancels it, nothing schedules. */
    @Override
    public boolean isDisabled() {
        return row().isEmpty();
    }

    /** CRON patterns are deterministic (fixed UTC slots); a fixed-delay cadence shifts with each completed run. */
    @Override
    public boolean isDeterministic() {
        return row().map(row -> "CRON".equals(row.scheduleKind())).orElse(false);
    }

    private CronSchedule cronSchedule(String pattern) {
        return cronByPattern.computeIfAbsent(pattern, p -> new CronSchedule(p, ZoneOffset.UTC));
    }
}