package io.integrationplatform.routing.adapter.db;

/** Test lookups for {@link ApprovedTables}: every table approved, or none. */
final class ApprovedTablesFixtures {

    static final ApprovedTables ALL = (endpointId, table) -> true;
    static final ApprovedTables NONE = (endpointId, table) -> false;

    private ApprovedTablesFixtures() {
    }
}
