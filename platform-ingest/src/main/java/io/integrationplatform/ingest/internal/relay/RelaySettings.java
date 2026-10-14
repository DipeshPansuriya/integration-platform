package io.integrationplatform.ingest.internal.relay;

import java.time.Duration;
import java.util.Objects;

/**
 * The relay's control settings (T-191, E8-F1-S3; ADR-005 Decision 12): the Kafka circuit breaker, the publish
 * backoff of an unacknowledged row's bucket, and the outbox-age alert threshold. "Thresholds are configuration;
 * these are the team defaults" - {@link #defaults()} carries them; tests pass shorter values so a 60 s alert is
 * proven in seconds.
 *
 * @param breakerFailureThreshold consecutive failed publishes that open the one Kafka breaker
 * @param breakerWaitInOpen       how long the open breaker keeps the relay from polling before a half-open trial
 * @param backoffInitial          the first wait before an unacknowledged row's bucket is tried again (1 s)
 * @param backoffMax              the cap on that wait (30 s)
 * @param backoffRandomization    the jitter factor (0 = none, 0.5 = +/- 50 %)
 * @param outboxAgeAlert          the age of the oldest due unpublished row that raises the alert (60 s)
 */
public record RelaySettings(int breakerFailureThreshold, Duration breakerWaitInOpen, Duration backoffInitial,
        Duration backoffMax, double backoffRandomization, Duration outboxAgeAlert) {

    public RelaySettings {
        if (breakerFailureThreshold < 1) {
            throw new IllegalArgumentException("breakerFailureThreshold must be at least 1");
        }
        Objects.requireNonNull(breakerWaitInOpen, "breakerWaitInOpen");
        Objects.requireNonNull(backoffInitial, "backoffInitial");
        Objects.requireNonNull(backoffMax, "backoffMax");
        Objects.requireNonNull(outboxAgeAlert, "outboxAgeAlert");
        if (backoffRandomization < 0 || backoffRandomization >= 1) {
            throw new IllegalArgumentException("backoffRandomization must be in [0, 1)");
        }
    }

    /** The ADR-005 Decision 12 defaults: breaker 5 failures / 30 s open, backoff 1 s doubling to 30 s +/- 50 %, alert 60 s. */
    public static RelaySettings defaults() {
        return new RelaySettings(5, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(30), 0.5,
                Duration.ofSeconds(60));
    }
}
