package io.integrationplatform.delivery.port;

import java.util.Collection;
import java.util.UUID;

/**
 * Stage-1 delivery writes (E9 seam, arjun-fullstack, F14): the delivery insert and the message's
 * {@code ACCEPTED -> ROUTED} guard, in stage 1's one transaction.
 *
 * <p>Signature seam only - lane C implements it (T-117, T-178) and proves the contract on the real
 * partitioned tables in a container test, per the {@link DeliveryRetryStore} pattern. Every method runs in the
 * caller's open, tenant-bound transaction and never opens its own (R08/R11); 0-rows-means-no-effect shapes report
 * {@code false} or a count, never an exception.
 *
 * <p>ADR-014 (ACCEPTED, replaced the impossible verbatim form): {@code delivery.created_at} is the message's
 * {@code accepted_at} - read from the message row stage 1 has just locked, never a free instant - so the idempotency
 * key is the UNIQUE triple {@code (message_id, route_id, created_at)}:
 * {@code ON CONFLICT (message_id, route_id, created_at) DO NOTHING} is the only supported conflict form on the
 * range-partitioned {@code delivery} table. There is NO {@code delivery_route} table.
 */
public interface Stage1Deliveries {

    /**
     * Inserts one delivery per matching route, each pinned to its own target contract and mapping version
     * (E9-C3), with {@code created_at = acceptedAt} (the message's {@code accepted_at} copied by the caller from
     * the message row it locked - ADR-014). Idempotent: a delivery that already exists for
     * {@code (message_id, route_id, created_at)} is not inserted again (ADR fix m-817272 B2: re-consuming the
     * message's {@code message.accepted} record after a crash or rebalance creates 0 new deliveries).
     *
     * @return how many deliveries were newly inserted (0 on a full re-consume)
     */
    int insertPerRoute(Collection<Stage1Delivery> deliveries);

    /**
     * The {@code ACCEPTED -> ROUTED} guard in the same transaction: updates the message row only from
     * {@code ACCEPTED} to {@code ROUTED}, conditional on {@code state = 'ACCEPTED'}.
     *
     * @return {@code false} when 0 rows changed (already routed - the fan-out above was a no-op too); the caller
     *     acknowledges the record either way
     */
    boolean markMessageRouted(UUID messageId);
}