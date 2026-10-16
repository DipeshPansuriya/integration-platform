package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.domain.delivery.OutcomeClassifier;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetCall;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLException;

/**
 * The API target adapter (E10 narrow, T-050; ADR-015: adapters live in {@code routing/adapter/**}). One call of
 * the layer-1 route ({@code TargetCall}), in order: the endpoint's row read through {@code TargetEndpointViews}
 * (tenant-scoped) -> the destination guard (reuse - never its own URL policy; the dialled address is the pinned
 * one) -> the one {@code HttpSend} try with the endpoint's method, timeout and {@code Idempotency-Key} (the
 * delivery id, ADR-011 Decision 4) -> the answer classified ONCE by {@code OutcomeClassifier} - any failure
 * leaves as a {@code TargetFaultException} carrying its class, never a status or a raw exception, so the
 * layer-1 route's redelivery follows one register (ADR-010 section 5).
 *
 * <p>Narrow-slice decisions: only {@code NO_AUTH} endpoints send (a credential-based method raises
 * {@code TRANSIENT} - the delivery waits for the E10 credentials slice instead of dead-lettering on a missing
 * feature); an endpoint whose request body the {@link ApiTargetBody} port cannot produce raises
 * {@code PERMANENT}; redirects are never followed (a redirect is a new destination the guard would have to
 * check first - answered as its own PERMANENT class status).
 */
public class ApiTargetCall implements TargetCall {

    private final TargetEndpointViews views;
    private final DestinationGuard guard;
    private final ApiTargetBody bodyPort;
    private final HttpSend sender;

    public ApiTargetCall(TargetEndpointViews views, DestinationGuard guard, ApiTargetBody bodyPort, HttpSend sender) {
        this.views = views;
        this.guard = guard;
        this.bodyPort = bodyPort;
        this.sender = sender;
    }

    @Override
    public void call(Stage2Send send) {
        TargetEndpointView view = views.byEndpointId(send.targetEndpointId()).orElseThrow(() ->
                new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(), "the send's endpoint "
                        + send.targetEndpointId() + " has no target_endpoint row for the bound tenant - the"
                        + " route resolution and the endpoint are out of step; nothing sent"));
        if (!"NO_AUTH".equals(view.authMethod())) {
            throw new TargetFaultException.TransientFaultException("the endpoint's auth method '"
                    + view.authMethod() + "' is not supported in this slice (E10 credentials landing); the"
                    + " delivery waits instead of dead-lettering on a missing feature");
        }
        ApiTargetBody.ApiWireBody body = bodyPort.bodyFor(send).orElseThrow(() ->
                new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(), "no transform result exists"
                        + " for delivery " + send.deliveryId() + " - the pipeline sent stage 2 without one;"
                        + " the transform lane produces it"));
        CheckedDestination destination;
        try {
            destination = guard.check(view.baseUri());
        } catch (DestinationRefusedException refused) {
            throw new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(), "the destination of "
                    + view.baseUri() + " is refused (" + refused.refusal() + "): nothing is sent to it");
        } catch (UnknownHostException unresolved) {
            throw new TargetFaultException.TransientFaultException("the allow-listed host of "
                    + view.baseUri() + " resolves to nothing (the address's fault, not the destination's)");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", body.contentType());
        // the endpoint is addressed per delivery: the key ADR-011 Decision 4 requires on every call
        headers.put("Idempotency-Key", send.deliveryId().toString());
        HttpSend.Response response;
        try {
            response = sender.send(destination, view.httpMethod(), headers, body.bytes(), view.timeoutMs());
        } catch (SocketTimeoutException slow) {
            throw fault(OutcomeClassifier.ofTimeout(view.idempotentReceive()),
                    "the send cut its connection at the endpoint's " + view.timeoutMs() + " ms timeout - the"
                    + " request may have been processed");
        } catch (SSLException tls) {
            throw new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(), "the TLS handshake with "
                    + view.baseUri().getHost() + " failed - the destination's identity cannot be verified"
                    + " (the refusal family, as the reachability check keeps it); nothing was sent");
        } catch (IOException connectFailed) {
            throw new TargetFaultException.TransientFaultException("the connection to "
                    + view.baseUri().getHost() + " failed before or during the send: " + connectFailed.getMessage());
        }
        OutcomeClass outcome = OutcomeClassifier.ofHttpStatus(response.status(), view.idempotentReceive(),
                view.errorDictionary());
        if (outcome != OutcomeClass.SUCCESS) {
            throw fault(outcome, "the target answered HTTP " + response.status() + " for"
                    + " delivery " + send.deliveryId() + ": class " + outcome);
        }
    }

    /** A classified failure as the layer-1 route redelivers it: TRANSIENT must leave as the redelivering subtype. */
    private static TargetFaultException fault(OutcomeClass outcome, String message) {
        return outcome == OutcomeClass.TRANSIENT
                ? new TargetFaultException.TransientFaultException(message)
                : new TargetFaultException(outcome, message);
    }
}
