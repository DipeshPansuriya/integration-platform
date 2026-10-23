package io.integrationplatform.config.system.application;

import java.util.Optional;
import java.util.UUID;

/**
 * T178a: the approval's question to the target database - is the restricted account wider than INSERT and SELECT on the
 * table being approved? The composition root answers it from routing's own {@code DbAccountWidth} port, backed by its
 * credentialed privilege probe (config may not depend on routing). Fail closed: anything but a clean "narrow" answer is a refusal.
 */
@FunctionalInterface
public interface DbAccountWidthCheck {

    /**
     * <p>T203: "narrow" means nothing beyond INSERT/SELECT on the table, no role attribute (SUPERUSER, BYPASSRLS,
     * CREATEROLE, CREATEDB, REPLICATION), no dangerous predefined role, no CREATE right on the database or a usable
     * schema, and no DELETE/TRUNCATE/TRIGGER/UPDATE/REFERENCES on another relation of the schema. It is NOT a claim
     * about function EXECUTE, other schemas, TEMP, or grants made after approval (width is read at approval, not at
     * send); the routing implementation documents the scope.
     *
     * @param settingsJson the system's DB settings with {@code table} set to the table being approved
     * @return empty when the account is narrow; else the refusal text (starts with {@code DB_ACCOUNT_TOO_WIDE} or
     *         {@code DB_ACCOUNT_UNVERIFIED})
     */
    Optional<String> refusalFor(UUID systemId, String settingsJson);
}
