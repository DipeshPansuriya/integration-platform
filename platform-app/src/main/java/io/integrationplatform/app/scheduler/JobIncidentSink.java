package io.integrationplatform.app.scheduler;

import java.time.Instant;

/**
 * E14 T-056 + T-062 (ADR-012 sections 3 and 6): the run/alert port. One {@code scheduler.job_run} row per executed
 * run and one {@code scheduler.job_alert} row per incident, unique on (job_type, scheduled_for, kind MISSED |
 * FAILED) so the same incident never alerts twice - {@link #raiseAlert} returns whether a row was actually
 * inserted. Implemented by {@link JobIncidentJdbcSink}; the JVM suites replace it with a fake.
 */
public interface JobIncidentSink {

    /**
     * Records one alert for the incident; returns true when the incident was new (the unique constraint says no).
     *
     * @param errorClass the exception class name, {@code MaxRunSecondsExceeded}, or null for a MISSED incident
     */
    boolean raiseAlert(String jobType, Instant scheduledFor, String kind, String errorClass, String message);

    void recordRun(JobRunRow row);
}