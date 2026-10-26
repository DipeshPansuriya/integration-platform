package io.integrationplatform.routing.adapter.db;

import java.util.UUID;

/**
 * E16 F3: the send-time read of the tables a DB target may write to (tenant-scoped, under row-level security). The
 * answer comes from the approval table, never from the system settings a configurator edits. An implementation
 * THROWS when it cannot tell (the platform database is down): the caller retries, it never reads that as "allowed".
 */
@FunctionalInterface
public interface ApprovedTables {

    /**
     * Whether {@code table} is APPROVED for the DB system behind {@code targetEndpointId} AND the approval still
     * matches the system's current host, port, database and account (Karan change 1).
     */
    boolean isApproved(UUID targetEndpointId, String table);
}
