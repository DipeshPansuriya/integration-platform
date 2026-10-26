package io.integrationplatform.routing.adapter.db;

import java.util.Optional;
import java.util.UUID;

/**
 * E16 F3 (design note task 11, Karan change 4): the restricted account's password for a DB system's test connection,
 * opened from the credential vault like the send path reuses ({@link JdbcDbTargets#DB_ACCOUNT_PASSWORD_FIELD} through
 * {@code SendTimeCredentials}) - the credential is endpoint-scoped, so the implementation opens it for the DB system's
 * own target endpoints. A failed or missing credential is empty - the caller refuses, it never probes passwordless.
 */
@FunctionalInterface
public interface DbSystemCredentials {

    /** A fresh copy for this one call - the caller wipes it after the probe; never a shared or cached array. */
    Optional<char[]> passwordOf(UUID systemId);
}