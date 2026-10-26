package io.integrationplatform.routing.adapter.db;

import java.util.List;
import java.util.UUID;

/**
 * E16 F3: the publish-time read - the DB tables of an interface's active routes that are not APPROVED (or whose
 * approval no longer matches the system's target). Empty means the publish may proceed.
 */
@FunctionalInterface
public interface UnapprovedTables {

    /** Each entry reads {@code systemCode.table}; plain identifiers only, safe to show a tenant admin. */
    List<String> ofInterface(UUID interfaceId);
}
