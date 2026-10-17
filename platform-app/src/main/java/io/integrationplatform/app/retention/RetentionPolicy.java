package io.integrationplatform.app.retention;

import java.time.Duration;
import java.time.Period;

/** RED stub (row327 T118): the figures are not agreed yet. */
public record RetentionPolicy(Duration logs, Period metrics, Duration traces, Period audit) {

    public static final RetentionPolicy AGREED = new RetentionPolicy(Duration.ZERO, Period.ZERO, Duration.ZERO,
            Period.ZERO);
}
