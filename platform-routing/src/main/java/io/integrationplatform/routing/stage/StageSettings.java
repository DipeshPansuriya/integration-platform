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
 * @param breakerName             the breaker's name and gauge tag: {@code database-<group>}, plus the loop number
 *                                when several loops share the group (T181), so no two loops share a gauge
 */
public record StageSettings(String group, int breakerFailureThreshold, Duration breakerWaitInOpen,
        String breakerName) {

    public StageSettings {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(breakerWaitInOpen, "breakerWaitInOpen");
        if (breakerFailureThreshold < 1) {
            throw new IllegalArgumentException("breakerFailureThreshold must be at least 1");
        }
        breakerName = breakerName != null ? breakerName : "database-" + group;
    }

    public StageSettings(String group, int breakerFailureThreshold, Duration breakerWaitInOpen) {
        this(group, breakerFailureThreshold, breakerWaitInOpen, null);
    }

    /**
     * The defaults for loop {@code index} (1-based) of {@code loops} in {@code group} (T181): one loop keeps the
     * name it always had; with several, each loop's breaker and gauges carry the loop number.
     */
    public static StageSettings forLoop(String group, int index, int loops) {
        if (loops < 1 || index < 1 || index > loops) {
            throw new IllegalArgumentException("loop " + index + " of " + loops + " is not a valid loop");
        }
        return new StageSettings(group, 5, Duration.ofSeconds(30),
                loops == 1 ? null : "database-" + group + "-" + index);
    }

    /** The ADR-005 Decision 12 defaults for {@code group}: 5 consecutive transient errors, 30 s open. */
    public static StageSettings defaults(String group) {
        return new StageSettings(group, 5, Duration.ofSeconds(30));
    }
}
