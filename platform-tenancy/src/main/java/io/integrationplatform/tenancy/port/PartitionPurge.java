package io.integrationplatform.tenancy.port;

import java.time.LocalDate;
import java.util.List;

/**
 * E14 T-142 / T-189 (ADR-014 Decision 3, ADR-012 section 4, ADR-016 Decision 1, architecture control T6): drops whole
 * day partitions - {@code message_D}, {@code payload_D} and {@code delivery_D} together - for every day before the
 * cutoff, except a day that still holds a DEAD_LETTERED or non-final delivery, which is kept and reported. Platform-wide
 * partition DDL by design: it takes no tenant from anywhere (the guard sees every tenant, inactive ones included) and
 * runs as the maintenance identity, never the application role.
 */
public interface PartitionPurge {

    /**
     * Purges every day partition strictly before {@code cutoff}. With {@code dryRun} nothing is dropped; the outcome
     * then lists what would be.
     */
    Outcome purgeBefore(LocalDate cutoff, boolean dryRun);

    /** The days dropped (or, in a dry run, that would be) and the days kept by the guard; no tenant id, no payload. */
    record Outcome(boolean dryRun, List<LocalDate> dropped, List<LocalDate> kept) {
    }
}
