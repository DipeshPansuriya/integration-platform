package io.integrationplatform.config.scheduler;

/**
 * E14 T-056 (ADR-012 Decision 2): one row of the closed register - the job definition IS the schedule. Fixed
 * columns of {@code scheduler.job_definition} (V2_2_1400); {@code parameters} stays the raw jsonb text (job-type
 * specific, never a tenant id - ADR-012 section 4).
 */
public record JobDefinitionRow(
        String jobType,
        String scheduleKind,
        String schedule,
        boolean enabled,
        boolean paused,
        int maxRunSeconds,
        int lateThresholdSeconds,
        String parameters) {

    /** A row only schedules when it exists, is enabled and is not paused (pausing stops the job). */
    public boolean active() {
        return enabled && !paused;
    }
}