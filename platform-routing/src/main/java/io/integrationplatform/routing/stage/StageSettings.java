package io.integrationplatform.routing.stage;

import java.time.Duration;
import java.util.Objects;

/**
 * A stage consumer's control settings (T-191, E8-F1-S3; ADR-005 Decision 12): the consumer group it runs in and
 * its one database circuit breaker - "5 consecutive transient errors pause all its partitions for the open
 * period". Thresholds are configuration; {@link #defaults(String)} carries the team defaults.
 *
 * @param group                   the consumer group (the breaker, the metrics and the alerts are per group)
 * @param breakerFailureThreshold consecutive transient database errors that open the breaker (5)
 * @param breakerWaitInOpen       the open period every partition of the group stays paused (30 s)
 */
public record StageSettings(String group, int breakerFailureThreshold, Duration breakerWaitInOpen) {

    public StageSettings {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(breakerWaitInOpen, "breakerWaitInOpen");
        if (breakerFailureThreshold < 1) {
            throw new IllegalArgumentException("breakerFailureThreshold must be at least 1");
        }
    }

    /** The ADR-005 Decision 12 defaults for {@code group}: 5 consecutive transient errors, 30 s open. */
    public static StageSettings defaults(String group) {
        return new StageSettings(group, 5, Duration.ofSeconds(30));
    }
}
