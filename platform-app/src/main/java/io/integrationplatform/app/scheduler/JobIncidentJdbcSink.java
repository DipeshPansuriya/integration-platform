package io.integrationplatform.app.scheduler;

import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * E14 T-056 + T-062: writes the run/alert rows. A failed raise or run write must never take the scheduler down -
 * the caller ({@link RunRecordingSchedulerListener}, {@link JobOverdueDetector}) catches it; the insert itself
 * relies on {@code job_alert_incident_uq} (V2_2_1400) plus {@code on conflict do nothing}, so the same incident
 * on (job_type, scheduled_for, kind) can never be alerted twice - even across app instances.
 */
@Repository
public class JobIncidentJdbcSink implements JobIncidentSink {

    private final JdbcTemplate jdbc;

    public JobIncidentJdbcSink(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public boolean raiseAlert(String jobType, Instant scheduledFor, String kind, String errorClass, String message) {
        int inserted = jdbc.update("""
                        insert into scheduler.job_alert (job_type, scheduled_for, kind, error_class, message)
                        values (?, ?, ?, ?, ?)
                        on conflict (job_type, scheduled_for, kind) do nothing""",
                jobType, Timestamp.from(scheduledFor), kind, errorClass, message);
        return inserted == 1;
    }

    @Override
    public void recordRun(JobRunRow row) {
        jdbc.update("""
                        insert into scheduler.job_run
                            (job_type, scheduled_for, started, finished, instance, outcome, is_late, error_class, counts)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                row.jobType(),
                row.scheduledFor() == null ? null : Timestamp.from(row.scheduledFor()),
                row.started() == null ? null : Timestamp.from(row.started()),
                row.finished() == null ? null : Timestamp.from(row.finished()),
                row.instance(),
                row.outcome(),
                row.isLate(),
                row.errorClass(),
                row.counts());
    }
}