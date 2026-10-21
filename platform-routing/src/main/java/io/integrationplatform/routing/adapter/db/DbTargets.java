package io.integrationplatform.routing.adapter.db;

import java.util.Optional;
import java.util.UUID;

/**
 * T-149: read port over a DB endpoint's stored settings and restricted account (the JdbcDbTargets impl is wired
 * in {@code DbAdapterConfiguration}). {@code password} is zeroed by {@code DbTargetCall} after the attempt.
 */
@FunctionalInterface
public interface DbTargets {

    /** The tenant's DB target for {@code endpointId}, or empty when the endpoint is not this tenant's DB target. */
    Optional<DbTarget> byEndpointId(UUID endpointId);

    /** The endpoint's target: its registry settings and the restricted account's opened password (may be null). */
    record DbTarget(DbSettings settings, char[] password) {
    }
}