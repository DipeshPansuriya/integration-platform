package io.integrationplatform.app.scheduler;

import java.time.Instant;

/**
 * E14 T-056 (ADR-012 sections 3 and 4): one {@code scheduler.job_run} row per executed run - outcome SUCCEEDED /
 * FAILED / MISSED, the late flag, instance, error class. Counts stays jsonb text (per-tenant counts are forbidden
 * in the scheduler tables - ADR-012 section 4); there is no payload and no tenant id anywhere in this record.
 */
public record JobRunRow(
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