package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import io.integrationplatform.tenancy.port.SchedulerRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The platform-wide JDBC side of the job scheduler's registry tables (E14 T-056/T-060/T-062; ADR-012 section 4), next
 * to {@link JdbcLoginBootstrap} and {@link JdbcClientBootstrap}: the scheduler's tables carry no tenant id and no row
 * of them is tenant data, so these statements deliberately run WITHOUT the tenant predicate - the reviewed crossing
 * that lets a read of {@code scheduler.job_definition} / {@code scheduler.scheduled_tasks} / a write of
 * {@code scheduler.job_run} / {@code scheduler.job_alert} exist at all. Every value is a bound parameter (R11); the
 * SQL is owned here, not by the callers. The arch-test rule (T-003) refuses any second scheduler call site, so a
 * module that needs another statement of these tables adds it HERE, not a new JdbcTemplate.
 */
@TenantBoundaryCrossing("ADR-012 section 4")
public final class JdbcSchedulerRegistry implements SchedulerRegistry {

    /** The closed register (V2_2_1400's scheduler schema); the columns JobScheduleSync reads, in its order. */
    private static final String SELECT_JOBS =
            "select job_type, schedule_kind, schedule, enabled, paused, max_run_seconds,"
                    + " late_threshold_seconds, coalesce(parameters::text, '') as parameters"
                    + " from scheduler.job_definition";

    /** Unclaimed executions past their register row's late threshold - the overdue detector's poll. */
    private static final String SELECT_OVERDUE = """
            select st.task_name, st.task_instance, st.execution_time
            from scheduler.scheduled_tasks st
            join scheduler.job_definition jd on jd.job_type = st.task_name
            where st.picked = false
              and st.execution_time < now() - make_interval(secs => jd.late_threshold_seconds)
            order by st.execution_time""";

    /** The same lateness as a single figure for the Prometheus gauge (0.0 when nothing is overdue). */
    private static final String SELECT_OLDEST_OVERDUE = """
            select coalesce(max(extract(epoch from (now() - st.execution_time))), 0)
            from scheduler.scheduled_tasks st
            join scheduler.job_definition jd on jd.job_type = st.task_name
            where st.picked = false
              and st.execution_time < now() - make_interval(secs => jd.late_threshold_seconds)""";

    private static final String INSERT_RUN = """
            insert into scheduler.job_run
                (job_type, scheduled_for, started, finished, instance, outcome, is_late, error_class, counts)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String INSERT_ALERT = """
            insert into scheduler.job_alert (job_type, scheduled_for, kind, error_class, message)
            values (?, ?, ?, ?, ?)
            on conflict (job_type, scheduled_for, kind) do nothing""";

    private final JdbcTemplate jdbc;

    /** Direct construction is for the container suite (it builds its own instances); production wires the bean. */
    public JdbcSchedulerRegistry(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    public JdbcSchedulerRegistry(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public <T> List<T> jobDefinitions(RowMapper<T> mapper) {
        return jdbc.query(SELECT_JOBS, mapper);
    }

    @Override
    public <T> List<T> jobDefinition(String jobType, RowMapper<T> mapper) {
        return jdbc.query(SELECT_JOBS + " where job_type = ?", mapper, jobType);
    }

    @Override
    public <T> List<T> overdueExecutions(RowMapper<T> mapper) {
        return jdbc.query(SELECT_OVERDUE, mapper);
    }

    @Override
    public double oldestOverdueSeconds() {
        Double oldest = jdbc.queryForObject(SELECT_OLDEST_OVERDUE, Double.class);
        return oldest == null ? 0.0 : oldest;
    }

    @Override
    public void recordRun(JobRunEntry run) {
        jdbc.update(INSERT_RUN,
                run.jobType(),
                run.scheduledFor() == null ? null : Timestamp.from(run.scheduledFor()),
                run.started() == null ? null : Timestamp.from(run.started()),
                run.finished() == null ? null : Timestamp.from(run.finished()),
                run.instance(),
                run.outcome(),
                run.isLate(),
                run.errorClass(),
                run.counts());
    }

    @Override
    public boolean recordAlert(String jobType, Instant scheduledFor, String kind, String errorClass, String message) {
        int inserted = jdbc.update(INSERT_ALERT,
                jobType, Timestamp.from(scheduledFor), kind, errorClass, message);
        return inserted == 1;
    }
}