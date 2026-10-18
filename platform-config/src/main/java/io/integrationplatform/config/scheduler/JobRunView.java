package io.integrationplatform.config.scheduler;

import java.time.Instant;
import java.util.UUID;

/**
 * E14 T-059 (E14-C9): one {@code scheduler.job_run} row as the operator's run search lists it - outcome, start, end
 * and error. A MISSED run never started, so {@code started} and {@code finished} are null and {@code scheduledFor}
 * is its due time. {@code counts} stays the raw jsonb text; no tenant id anywhere (ADR-012 section 4).
 */
public record JobRunView(
        UUID id,
        String jobType,
        Instant scheduledFor,
        Instant started,
        Instant finished,
        String instance,
        String outcome,
        boolean late,
        String errorClass,
        String counts) {
}
