package io.integrationplatform.routing.stage2;

import java.util.UUID;

/**
 * Captures the target's HTTP response after a successful delivery (IB08 Phase 1). The callback
 * engine (Phase 3) reads captured rows whose route has a callback_mode and sends them back to
 * the originating system. A no-op implementation is safe: uncaptured responses are simply not
 * available for callbacks, preserving the existing fire-and-forget behaviour.
 */
public interface ResponseCapturePort {

    void capture(UUID deliveryId, int status, String contentType, byte[] body);

    ResponseCapturePort NOOP = (deliveryId, status, contentType, body) -> {};
}
