package io.integrationplatform.delivery.replay;

import java.util.Optional;
import java.util.UUID;

/**
 * T172 (project-owner decision 2026-10-22: a route without a mapping is replayable): the contract version an operator
 * replay or retry pins for a delivery that has no mapping version - the target endpoint's active contract, i.e. the
 * newest APPROVED {@code TARGET_REQUEST} contract version of the endpoint's system. Runs in the caller's open,
 * tenant-stamped transaction. Implemented where the configuration tables are visible (platform-routing); empty when
 * the endpoint has no approved contract, which keeps the delivery refused as not pinned.
 */
@FunctionalInterface
public interface ActiveTargetContracts {

    Optional<UUID> activeContractVersionOf(UUID targetEndpointId);
}
