package io.integrationplatform.app.scheduler;

import io.integrationplatform.tenancy.port.SchedulerRegistry;
import java.time.Instant;
import org.springframework.stereotype.Repository;

/**
 * E14 T-056 + T-062: writes the run/alert rows. A failed raise or run write must never take the scheduler down -
 * the caller ({@link RunRecordingSchedulerListener}, {@link JobOverdueDetector}) catches it; the insert itself
 * relies on {@code job_alert_incident_uq} (V2_2_1400) plus {@code on conflict do nothing}, so the same incident
 * on (job_type, scheduled_for, kind) can never be alerted twice - even across app instances. The statements live in
 * the reviewed platform crossing ({@code JdbcSchedulerRegistry}, reason "ADR-012 section 4" - the scheduler tables
 * are platform-wide, no row is tenant data); the arch-test tenant filter rule refuses a second scheduler call site.
 */
@Repository
public class JobIncidentJdbcSink implements JobIncidentSink {

    private final SchedulerRegistry registry;

    public JobIncidentJdbcSink(SchedulerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean raiseAlert(String jobType, Instant scheduledFor, String kind, String errorClass,
            String message) {
        return registry.recordAlert(jobType, scheduledFor, kind, errorClass, message);
    }

    @Override
    public void recordRun(JobRunRow row) {
        registry.recordRun(new SchedulerRegistry.JobRunEntry(
                row.jobType(),
                row.scheduledFor(),
                row.started(),
                row.finished(),
                row.instance(),
                row.outcome(),
                row.isLate(),
                row.errorClass(),
                row.counts()));
    }
}