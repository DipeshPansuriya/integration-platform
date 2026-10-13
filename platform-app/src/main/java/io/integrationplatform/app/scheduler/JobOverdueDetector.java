package io.integrationplatform.app.scheduler;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * E14 T-062 (ADR-012 section 6): missed-run detection that does NOT depend on db-scheduler's polling. Every poll
 * reads {@code scheduler.scheduled_tasks} fresh: an execution that was never picked and is older than its job
 * row's {@code late_threshold_seconds} is a MISSED incident - exactly one alert (the unique constraint on
 * (job_type, scheduled_for, kind) says no even across app instances), one MISSED run row with the late flag and
 * no instance (it never started), and the stuck pending execution is cancelled so the next sync can reschedule.
 * The oldest overdue seconds feed the Prometheus gauge at scrape time.
 */
@Component
public class JobOverdueDetector {

    private static final Logger LOG = LoggerFactory.getLogger(JobOverdueDetector.class);

    private static final String OVERDUE_SQL = """
            select st.task_name, st.task_instance, st.execution_time
            from scheduler.scheduled_tasks st
            join scheduler.job_definition jd on jd.job_type = st.task_name
            where st.picked = false
              and st.execution_time < now() - make_interval(secs => jd.late_threshold_seconds)
            order by st.execution_time""";

    private final JdbcTemplate jdbc;
    private final JobIncidentSink sink;
    private final SchedulerClient client;

    public JobOverdueDetector(DataSource dataSource, JobIncidentSink sink, SchedulerClient client) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.sink = sink;
        this.client = client;
    }

    private record Overdue(String jobType, String instance, Instant dueAt) {
    }

    /** Records every overdue unclaimed execution; returns how many MISSED incidents were new. */
    public int detectAndRecord() {
        List<Overdue> overdue = jdbc.query(OVERDUE_SQL, (rs, rowNum) -> new Overdue(
                rs.getString("task_name"), rs.getString("task_instance"),
                rs.getObject("execution_time", Instant.class)));
        int incidents = 0;
        for (Overdue o : overdue) {
            try {
                if (!sink.raiseAlert(o.jobType(), o.dueAt(), "MISSED", null,
                        "execution due " + o.dueAt() + " was never picked within its late threshold")) {
                    continue; // this incident already alerted (unique constraint) - never alert twice
                }
                sink.recordRun(new JobRunRow(o.jobType(), o.dueAt(), null, null, null, "MISSED", true, null, null));
                client.cancel(TaskInstanceId.of(o.jobType(), o.instance()));
                incidents++;
            } catch (RuntimeException e) {
                LOG.error("missed-detection for {} at {} failed; detection continues", o.jobType(), o.dueAt(), e);
            }
        }
        return incidents;
    }

    /** The oldest overdue unclaimed execution in seconds - 0.0 when nothing is overdue (the gauge's scrape view). */
    public double oldestOverdueSeconds() {
        Double oldest = jdbc.queryForObject("""
                select coalesce(max(extract(epoch from (now() - st.execution_time))), 0)
                from scheduler.scheduled_tasks st
                join scheduler.job_definition jd on jd.job_type = st.task_name
                where st.picked = false
                  and st.execution_time < now() - make_interval(secs => jd.late_threshold_seconds)""",
                Double.class);
        return oldest == null ? 0.0 : oldest;
    }
}