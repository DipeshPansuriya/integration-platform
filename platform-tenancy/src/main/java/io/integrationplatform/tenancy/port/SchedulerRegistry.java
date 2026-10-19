package io.integrationplatform.tenancy.port;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;

/**
 * Platform-wide access to the job scheduler's registry tables (E14 T-056/T-060/T-062; ADR-012 section 4): the closed
 * register {@code scheduler.job_definition}, db-scheduler's {@code scheduler.scheduled_tasks} and the run/alert rows of
 * {@code scheduler.job_run} and {@code scheduler.job_alert}. No row of these tables is tenant data (ADR-012 section 4:
 * jobs are platform-wide, no tenant id anywhere), so this port is the one reviewed boundary crossing that runs their
 * statements WITHOUT the tenant predicate - every other statement in the platform must go through
 * {@link io.integrationplatform.tenancy.TenantScopedRepository}. The adapter is annotated
 * {@code @TenantBoundaryCrossing("ADR-012 section 4")} and arch-test refuses any second scheduler call site.
 */
public interface SchedulerRegistry {

    /**
     * Reads {@code select job_definition} rows (the closed register). Conditions are compile-time constants only
     * (R11); the adapter owns every SQL fragment.
     */
    <T> List<T> jobDefinitions(RowMapper<T> mapper);

    /**
     * Reads {@code select job_definition ... where job_type = ?} - at most one row (job_type is the table's primary
     * key).
     */
    <T> List<T> jobDefinition(String jobType, RowMapper<T> mapper);

    /**
     * Reads every unclaimed execution ({@code scheduler.scheduled_tasks}) whose due time passed its register row's
     * {@code late_threshold_seconds} - the missed-run view the overdue detector polls independently of db-scheduler's
     * own picking.
     */
    <T> List<T> overdueExecutions(RowMapper<T> mapper);

    /**
     * The oldest overdue unclaimed execution's lateness in seconds; 0.0 when nothing is overdue. The Prometheus
     * gauge reads this at scrape time.
     */
    double oldestOverdueSeconds();

    /**
     * Writes one {@code scheduler.job_run} row (outcome SUCCEEDED / FAILED / MISSED). The insert itself relies on the
     * caller catching failures: a failed run write must never take the scheduler down (T-056).
     */
    void recordRun(JobRunEntry run);

    /**
     * Writes one {@code scheduler.job_alert} row; at the unique incident key {@code (job_type, scheduled_for, kind)}
     * the insert uses {@code on conflict do nothing}, so the same incident can never be alerted twice - even across
     * app instances (V2_2_1400's {@code job_alert_incident_uq}). Returns true only when a row was inserted.
     */
    boolean recordAlert(String jobType, Instant scheduledFor, String kind, String errorClass, String message);

    /**
     * Reads {@code scheduler.job_run} rows for the operator's run search (T-059, E14-C9), newest first. Each non-null
     * filter of the query adds one compile-time fragment with a bound value (R11); the date window is on the run's
     * start, or its due time when it never started (a MISSED run), so missed runs stay searchable by date.
     */
    <T> List<T> searchRuns(RunQuery query, RowMapper<T> mapper);

    /**
     * Pauses or resumes one register row (T-060, ADR-012 section 5): pausing records who and when, resuming clears
     * both. Returns the rows changed - 0 for an unknown job type.
     */
    int setPaused(String jobType, boolean paused, String by, Instant at);

    /** The port's {@code scheduler.job_run} row shape; a module's own run row maps onto it component by component. */
    record JobRunEntry(
            String jobType,
            Instant scheduledFor,
            Instant started,
            Instant finished,
            String instance,
            String outcome,
            boolean isLate,
            String errorClass,
            String counts) {
    }

    /**
     * The run search's filters; null means no filter on that column. {@code from} is inclusive, {@code to}
     * exclusive; the caller validates (the adapter only binds).
     */
    record RunQuery(String jobType, String outcome, Instant from, Instant to, int limit) {
    }
}