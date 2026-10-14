package io.integrationplatform.delivery;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one claim order of ADR-010 section 7 (T-193), as a single sequence on lane C's delivery tables:
 * ordering-key advisory lock, read the delivery {@code FOR UPDATE} where the state is claimable, evaluate the
 * hold, then {@code HELD} (with the hold event) or {@code SENDING} with a fresh claim token - all in the
 * caller's open transaction, so the lock is held to the commit and the commit is exactly one state.
 *
 * <p>0 rows at the read means a duplicate Kafka record or a delivery already held, claimed or final: the
 * outcome is {@link Outcome#NO_EFFECT} and the record is acknowledged with nothing changed. The tenant and the
 * transaction come from the caller ({@code TenantContext}, R08, R11); {@code platform-routing} runs this inside
 * its stage-2 claim transaction.
 *
 * <p>Two shapes (T-197): the bare E2 service runs the order directly in the caller's transaction; when the E9
 * helper is wired the order runs behind {@link io.integrationplatform.delivery.port.OrderedStateChange} - the
 * helper takes the locks (key first, then the delivery; the message-row lock is wave-1) and supplies the
 * transaction and the tenant stamp when the caller opened none, with the 40001/40P01 in-place retry on its own
 * transaction only.
 */
public final class DeliveryClaimService {

    /** The claim transaction's outcome for the consumed record. */
    public enum Outcome {

        /** {@code SENDING} with the fresh token; hand the work to the bulkhead. */
        CLAIMED,

        /** The hold applied: {@code HELD} with its event; acknowledge the record, wait for the release. */
        HELD,

        /** Nothing matched (duplicate or already claimed/final): acknowledge with no effect. */
        NO_EFFECT
    }

    private final JdbcDeliveryRepository repository;
    private final io.integrationplatform.delivery.port.OrderedStateChange orderedChange;

    /** The bare E2 shape (T-193): the caller supplies the whole transaction; the claim order runs directly. */
    public DeliveryClaimService(JdbcDeliveryRepository repository) {
        this(repository, null);
    }

    /**
     * The E9 shape (T-197): the claim order runs behind the ONE lock-order helper, which supplies the
     * transaction and the tenant stamp when the caller opened none and joins the caller transaction otherwise -
     * the 40001/40P01 in-place retry applies only to the helper's own transaction.
     */
    public DeliveryClaimService(JdbcDeliveryRepository repository,
            io.integrationplatform.delivery.port.OrderedStateChange orderedChange) {
        this.repository = repository;
        this.orderedChange = orderedChange;
    }

    /**
     * Runs the claim order for one delivery.
     *
     * @param orderingKey the record's ordering key (tenant, interface, business key) - {@code null} or blank
     *     when the interface's ordering is {@code NONE} (ADR-011 Decision 6), which skips the advisory lock
     * @param deliveryId  the delivery named by the consumed {@code platform.delivery.due} record
     * @param claimToken  the fresh token the {@code SENDING} state will require for every later update
     * @param at          the transition time stored with the event
     */
    public Outcome claim(String orderingKey, UUID deliveryId, UUID claimToken, Instant at) {
        if (orderedChange != null) {
            return orderedChange.run(orderingKey, null, List.of(deliveryId),
                    () -> claimBody(deliveryId, claimToken, at));
        }
        if (orderingKey != null && !orderingKey.isBlank()) {
            repository.lockOrderingKey(orderingKey); // the bare path locks within the caller's transaction
        }
        return claimBody(deliveryId, claimToken, at);
    }

    /** The read, hold check and conditional state - the key lock is already held (bare path or helper). */
    private Outcome claimBody(UUID deliveryId, UUID claimToken, Instant at) {
        Optional<JdbcDeliveryRepository.Claimable> row = repository.readForClaim(deliveryId);
        if (row.isEmpty()) {
            return Outcome.NO_EFFECT;
        }
        if (repository.holdApplies(row.get())) {
            if (!repository.hold(deliveryId, at)) {
                return Outcome.NO_EFFECT;
            }
            return Outcome.HELD;
        }
        if (!repository.claimSend(deliveryId, claimToken, at)) {
            return Outcome.NO_EFFECT;
        }
        return Outcome.CLAIMED;
    }
}