package io.integrationplatform.scheduler.api;

import java.time.Instant;

/**
 * E14 seam: the registry/runner interface for recording one job run with its timestamps, mirroring the audit-writer
 * seam (ADR-012 section 4: {@code scheduler.job_run}). Implementations (JDBC-backed, test doubles) own the SQL and
 * any tenant-boundary crossing; this module carries the contract only, so parallel lanes compile against it
 * without importing platform-config or platform-app.
 */
public interface JobRunRecorder {

    /** Records one run row. Failures must never take the scheduler down (T-056): callers catch. */
    void recordRun(JobRun run);
/** One job run's recordable shape: type, due time, start, finish, outcome (and optional class/error text). */
    record JobRun(
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
}
