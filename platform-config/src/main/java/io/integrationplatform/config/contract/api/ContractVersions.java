package io.integrationplatform.config.contract.api;

import java.util.Optional;

/**
 * T-106 (ADR-011 Decision 3 rules 3 and 5): the config module's public port for "which contract version does a
 * message use now". Rows only - a version added and approved on a running system is answered at once, with no deploy
 * and no restart. Runs inside the caller's transaction, for the bound tenant (R08).
 *
 * <p>Usable under ADR-011 Decision 3 means APPROVED: a contract version has no publish step of its own (publishing
 * belongs to the interface version, rule 6), and RETIRED means "no new pins" (rule 4), so a retired version is never
 * answered here although it stays readable through the admin API.
 */
public interface ContractVersions {

    /**
     * The newest APPROVED version (highest {@code major.minor}) of the target request contract of the bound tenant's
     * system {@code systemCode}; empty when the system has none.
     */
    Optional<UsableContractVersion> usableTargetVersion(String systemCode);
}
