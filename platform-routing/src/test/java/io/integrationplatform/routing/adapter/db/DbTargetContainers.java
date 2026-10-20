package io.integrationplatform.routing.adapter.db;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T-149 (E16-F1-S2, E16-C6): the DB-target test container descriptor - a SECOND PostgreSQL, the target's own
 * database, never the shared compose. The scenario's restricted account (SELECT+INSERT, no DDL) is created by
 * the test as the owner; passwords are fixed container-local strings (R07: never a real credential).
 */
public final class DbTargetContainers {

    static final String IMAGE = "postgres:17.11-alpine";

    private DbTargetContainers() {
    }

    /** The target's PostgreSQL, one per descriptor call. */
    public static PostgreSQLContainer target() {
        return new PostgreSQLContainer(IMAGE);
    }
}