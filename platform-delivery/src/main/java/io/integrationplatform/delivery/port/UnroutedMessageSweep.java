package io.integrationplatform.delivery.port;

import java.util.UUID;

/**
 * T-180 (E9 T72): the routing leg of the reconciler sweep, as the delivery-retry trigger sees it - a message still
 * {@code ACCEPTED} past its threshold lost its {@code message.accepted} record, so one sweep per active tenant
 * hands each such message back to routing stage 1. The delivery module never imports routing: routing implements
 * this port and the application wires the instance in.
 *
 * <p>The implementation binds the tenant itself and opens its own transactions; the trigger calls it with no
 * tenant bound and no transaction open.
 */
@FunctionalInterface
public interface UnroutedMessageSweep {

    /**
     * One sweep for one tenant.
     *
     * @return how many stuck messages were handed back to stage 1 without an error
     */
    int sweepOnce(UUID tenantId);

    /** No routing leg: the trigger's constructors without a sweep route nothing (the behaviour before T72). */
    static UnroutedMessageSweep none() {
        return tenantId -> 0;
    }
}
