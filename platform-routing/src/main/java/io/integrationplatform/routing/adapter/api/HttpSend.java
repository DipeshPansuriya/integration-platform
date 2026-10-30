package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.config.destination.CheckedDestination;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * The one place an HTTP request leaves the process for an API target (E10 narrow, T-050; ADR-015). The
 * implementation dialls the {@code CheckedDestination} the guard pinned - never a second DNS lookup - and
 * carries the endpoint's per-target timeout ({@code target_endpoint.timeout_ms}). A socket-level seam, like the
 * reachability check's diall: this is what makes the pinned address and the guard's refusals un-bypassable.
 *
 * <p>R13 on the caller: the layer-1 route owns transient redelivery of classified outcomes (ADR-010 section 5) -
 * this port performs exactly one call per invocation, one try, no retry of its own.
 */
public interface HttpSend {

    /**
     * Sends one request.
     *
     * @param destination the checked, pinned destination
     * @param method the endpoint's HTTP method
     * @param headers the request headers (Content-Type, Idempotency-Key)
     * @param body the request bytes (the transform result, verbatim)
     * @param timeoutMs the endpoint's whole-call timeout in milliseconds ({@code target_endpoint.timeout_ms},
     *        100..300000) - connect and read
     * @return the answer's status and body
     * @throws SocketTimeoutException the send cut its connection on the timeout (the request may have gone)
     * @throws IOException the send failed otherwise (connect refused, reset, TLS, a non-HTTP answer)
     */
    Response send(CheckedDestination destination, String method, Map<String, String> headers,
            byte[] body, int timeoutMs) throws IOException;

    /**
     * @param status the answer's HTTP status
     * @param body the answer's body (bounded read); empty for status-only answers
     * @param contentType the answer's Content-Type header; null when absent (IB08 Phase 1)
     * @param retryAfter the answer's Retry-After (delta-seconds or HTTP-date, floor zero); null when absent or
     *        unparseable (T96)
     */
    record Response(int status, byte[] body, String contentType, Duration retryAfter) {

        public Response(int status, byte[] body) {
            this(status, body, null, null);
        }
    }
}