package io.integrationplatform.app.retention;

import java.time.Duration;
import java.time.Period;

/**
 * Row327 T118 (E12-F2-S3, T-135): how long each kind of operational data is kept - PRD "Retention" (logs 30 days,
 * metrics 13 months, traces 7 days, audit 7 years) and architecture.md "Audit - partition drop after 7 years".
 *
 * <p>Fixed figures, not properties: they are agreed policy, and the audit figure is also the cutoff the database purge
 * ({@code public.audit_purge_expired()}, the {@code *__audit_retention.sql} migration) applies with no argument, so an
 * override here could only make the two disagree. {@code AuditRetentionMigrationTest} keeps them equal.
 * Payload (E14 T-142): message, payload and delivery day partitions are dropped after 30 days by PAYLOAD_PURGE.
 * ponytail: the log, metric and trace figures are declared here but not yet enforced - no log shipper, metrics store
 * or trace backend runs in compose today (row327 Open, mahesh-architect's call).
 */
public record RetentionPolicy(Duration logs, Period metrics, Duration traces, Period audit, Period payload) {

    /** The agreed figures. */
    public static final RetentionPolicy AGREED = new RetentionPolicy(Duration.ofDays(30), Period.ofMonths(13),
            Duration.ofDays(7), Period.ofYears(7), Period.ofDays(30));
}
