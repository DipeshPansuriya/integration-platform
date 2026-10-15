package io.integrationplatform.routing.adapter.api;

import java.util.Optional;

/**
 * The seam the adapter takes the request bytes from (E10 narrow): who turns a delivery into the target's wire
 * shape is the transform lane's work (E7) - until its port lands here, this is the one place the adapter asks
 * for the body, and the composition wiring will implement it from the transform result. The adapter reads no
 * contract detail itself (ADR-015: translation is the transform module's business; the send is byte-level).
 */
public interface ApiTargetBody {

    /**
     * @param send the stage-2 send being attempted
     * @return the wire body for this send, or empty when no transform result exists for it - the adapter then
     *         refuses the call (PERMANENT: nothing it could send)
     */
    Optional<ApiWireBody> bodyFor(io.integrationplatform.routing.stage2.Stage2Send send);

    /** The body bytes already in the target's contract and content type. */
    record ApiWireBody(String contentType, byte[] bytes) {

        public ApiWireBody {
            java.util.Objects.requireNonNull(contentType, "contentType");
            java.util.Objects.requireNonNull(bytes, "bytes");
        }
    }
}