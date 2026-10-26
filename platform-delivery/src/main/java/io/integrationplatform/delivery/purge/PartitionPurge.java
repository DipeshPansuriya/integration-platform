package io.integrationplatform.delivery.purge;

import java.time.LocalDate;
import java.util.List;

/**
 * E14 T-142 / T-189 (ADR-012, architecture.md "Payload retention"): drops whole day partitions - {@code message_D},
 * {@code payload_D} and {@code delivery_D} together - for every day before the cutoff, except a day that still holds
 * a DEAD_LETTERED or non-final delivery, which is kept and reported. Tenant-blind DDL by design: it never reads a
 * tenant from anywhere and runs as the maintenance identity, never the application role (T6).
 */
public interface PartitionPurge {

    /** Purges every day partition strictly before {@code cutoff}. */
    Outcome purgeBefore(LocalDate cutoff);

    /** The days dropped and the days kept by the guard; no tenant id and no per-tenant count. */
    record Outcome(List<LocalDate> dropped, List<LocalDate> kept) {
    }
}
