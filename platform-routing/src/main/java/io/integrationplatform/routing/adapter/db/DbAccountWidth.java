package io.integrationplatform.routing.adapter.db;

import java.util.Optional;
import java.util.UUID;

/**
 * T178a / B30: routing's answer to "is the restricted account wider than INSERT and SELECT on the table being
 * approved?". Routing owns this port so it names no config application type (ADR-017 Decision 6); the composition
 * root adapts it to config's approval check. Fail closed: anything but a clean "narrow" answer is a refusal.
 */
@FunctionalInterface
public interface DbAccountWidth {

    /**
     * @param settingsJson the system's DB settings with {@code table} set to the table being approved
     * @return empty when the account is narrow; else the refusal text (starts with {@code DB_ACCOUNT_TOO_WIDE} or
     *         {@code DB_ACCOUNT_UNVERIFIED})
     */
    Optional<String> refusalFor(UUID systemId, String settingsJson);
}
