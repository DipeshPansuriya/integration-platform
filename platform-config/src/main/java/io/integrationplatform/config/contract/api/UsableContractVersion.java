package io.integrationplatform.config.contract.api;

import java.util.Objects;
import java.util.UUID;

/**
 * A contract version a message may be processed and recorded under (T-106).
 *
 * @param id           the contract version id - the value a message or delivery records
 * @param contractId   the contract it belongs to
 * @param systemCode   the system the contract describes
 * @param role         the contract role (for example {@code TARGET_REQUEST})
 * @param versionLabel the {@code major.minor} label, for example {@code 2.0}
 */
public record UsableContractVersion(UUID id, UUID contractId, String systemCode, String role, String versionLabel) {

    public UsableContractVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(systemCode, "systemCode");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(versionLabel, "versionLabel");
    }
}
