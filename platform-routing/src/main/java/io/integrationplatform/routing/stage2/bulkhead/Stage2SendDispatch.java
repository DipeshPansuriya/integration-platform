package io.integrationplatform.routing.stage2.bulkhead;

import io.integrationplatform.routing.stage2.Stage2Send;

/**
 * The hand-off the bulkhead's pool thread performs (T-177): in production this is the stage-2 send route -
 * {@code producerTemplate.sendBody(LayerOneRouteBuilder.LAYER_ONE_SEND, send)} running the layer-1 route
 * (T-119) on the pool thread, which is where the target call lives. The dispatch owns the permit's lifecycle
 * and wraps this call so the permit always returns; nothing here opens transactions or touches state - the
 * outcome path stays the existing recorder machinery.
 */
public interface Stage2SendDispatch {

    /** Sends one claimed delivery on the target's bulkhead thread (called on that thread, never another). */
    void dispatch(Stage2Send send);
}
