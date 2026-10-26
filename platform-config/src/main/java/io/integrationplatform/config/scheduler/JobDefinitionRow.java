package io.integrationplatform.config.scheduler;

import java.time.Instant;

/**
 * E14 T-056 (ADR-012 Decision 2): one row of the closed register - the job definition IS the schedule. Fixed
 * columns of {@code scheduler.job_definition} (V2_2_1400); {@code parameters} stays the raw jsonb text (job-type
 * specific, never a tenant id - ADR-012 section 4); {@code pausedAt} is {@code paused_at}, set only while paused;
 * {@code stateChangedAt} is {@code state_changed_at} (V2_4_1320), stamped by a trigger whenever {@code enabled} or
 * {@code paused} changes - the one signal that also sees a disable or a resume.
 */
public record JobDefinitionRow(
        String jobType,
        String scheduleKind,
        String schedule,
        boolean enabled,
        boolean paused,
        int maxRunSeconds,
        int lateThresholdSeconds,
        String parameters,
        Instant pausedAt,
        Instant stateChangedAt) {

    /** A row without a pause timestamp (additive: the pre-pausedAt shape stays constructible). */
    public JobDefinitionRow(String jobType, String scheduleKind, String schedule, boolean enabled, boolean paused,
            int maxRunSeconds, int lateThresholdSeconds, String parameters) {
        this(jobType, scheduleKind, schedule, enabled, paused, maxRunSeconds, lateThresholdSeconds, parameters, null,
                null);
    }

    /** A row without a state-change stamp (additive: the pre-stateChangedAt shape stays constructible). */
    public JobDefinitionRow(String jobType, String scheduleKind, String schedule, boolean enabled, boolean paused,
            int maxRunSeconds, int lateThresholdSeconds, String parameters, Instant pausedAt) {
        this(jobType, scheduleKind, schedule, enabled, paused, maxRunSeconds, lateThresholdSeconds, parameters,
                pausedAt, null);
    }

    /** A row only schedules when it exists, is enabled and is not paused (pausing stops the job). */
    public boolean active() {
        return enabled && !paused;
    }
}