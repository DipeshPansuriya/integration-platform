package io.integrationplatform.routing.stage2;

import java.util.Objects;
import java.util.UUID;

/**
 * One stage-2 send the layer-1 route performs (T-119; ADR-010 section 4): everything one in-attempt send needs,
 * from the claim the stage-2 consumer already holds (the claim stays the existing
 * {@code DeliveryClaimHandler} path - this record changes nothing about the claim) - the target's
 * idempotent-receive declaration pinned from its row (ADR-011), carried so the outcome path classifies an
 * ambiguous failure per the target.
 *
 * @param deliveryId                      the delivery being sent
 * @param claimToken                      the token the claim wrote with SENDING
 * @param orderingKey                     the record's ordering key, or null when the interface's ordering is NONE
 * @param targetEndpointId                the pinned target endpoint (dispatches the target route instance)
 * @param targetDeclaresIdempotentReceive the pinned target's declaration (an AMBIGUOUS outcome then retries in
 *                                        layer 2 like a transient one, ADR-010 section 5)
 */
public record Stage2Send(UUID deliveryId, UUID claimToken, String orderingKey, UUID targetEndpointId,
        boolean targetDeclaresIdempotentReceive) {

    public Stage2Send {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(targetEndpointId, "targetEndpointId");
    }
}