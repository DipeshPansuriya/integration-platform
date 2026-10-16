package io.integrationplatform.routing.stage2.bulkhead;

import io.integrationplatform.delivery.DeliveryClaimService;
import java.util.UUID;

/**
 * The claim as the bulkhead dispatch sees it (T-177 seam): the existing
 * {@link io.integrationplatform.routing.claim.DeliveryClaimHandler#claim(String, UUID, UUID)} fits this
 * interface exactly ({@code handler::claim} is the production binding) - the dispatch holds no dependency on
 * the claim machinery beyond this shape, so the permit-before-claim order is testable without a database.
 */
public interface Stage2Claim {

    /** Runs the one claim transaction of ADR-010 section 7 for this delivery under this ordering key. */
    DeliveryClaimService.Outcome claim(String orderingKey, UUID deliveryId, UUID claimToken);
}
