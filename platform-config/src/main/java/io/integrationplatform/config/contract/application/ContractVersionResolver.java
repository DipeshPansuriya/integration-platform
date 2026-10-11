package io.integrationplatform.config.contract.application;

import io.integrationplatform.config.contract.api.ContractVersions;
import io.integrationplatform.config.contract.api.UsableContractVersion;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * T-106: the {@link ContractVersions} port over the contract rows - read on every call, never cached, so a version
 * approved a moment ago is the one the next message uses (0 deploys, 0 restarts; ADR-011 Decision 3 rule 5).
 */
public final class ContractVersionResolver implements ContractVersions {

    private final ContractAdminService contracts;

    public ContractVersionResolver(ContractAdminService contracts) {
        this.contracts = Objects.requireNonNull(contracts, "contracts");
    }

    @Override
    public Optional<UsableContractVersion> usableTargetVersion(String systemCode) {
        return contracts.newestApproved(systemCode, "TARGET_REQUEST")
                .map(version -> new UsableContractVersion(UUID.fromString(version.id()),
                        UUID.fromString(version.contractId()), version.systemCode(), version.role(),
                        version.versionLabel()));
    }
}
