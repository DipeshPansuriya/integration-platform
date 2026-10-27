package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.config.destination.ApiKeyHeaderName;
import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.domain.delivery.OutcomeClassifier;
import io.integrationplatform.routing.credential.OpenedCredentials;
import io.integrationplatform.routing.credential.TargetCallCredentialHolder;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetCall;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLException;

/**
 * The API target adapter (E10, T-050; ADR-015: adapters live in {@code routing/adapter/**}). One call of
 * the layer-1 route ({@code TargetCall}), in order: the endpoint's row read through {@code TargetEndpointViews}
 * (tenant-scoped) -> authentication applied from the endpoint's method and opened credentials -> the destination
 * guard (reuse - never its own URL policy; the dialled address is the pinned one) -> the one {@code HttpSend}
 * try with the endpoint's method, timeout and {@code Idempotency-Key} (the delivery id, ADR-011 Decision 4) ->
 * the answer classified ONCE by {@code OutcomeClassifier} - any failure leaves as a {@code TargetFaultException}
 * carrying its class, never a status or a raw exception, so the layer-1 route's redelivery follows one register
 * (ADR-010 section 5).
 *
 * <p>Supported auth methods (V2_4_1420): NO_AUTH (no credentials), BASIC (username:password),
 * BEARER_TOKEN (static token), API_KEY (header-based key), OAUTH2_CLIENT_CREDENTIALS (token exchange).
 * An endpoint whose request body the {@link ApiTargetBody} port cannot produce raises {@code PERMANENT};
 * redirects are never followed (a redirect is a new destination the guard would have to check first - answered
 * as its own PERMANENT class status).
 */
public class ApiTargetCall implements TargetCall {

    private final TargetEndpointViews views;
    private final DestinationGuard guard;
    private final ApiTargetBody bodyPort;
    private final HttpSend sender;
    private final CircuitBreakerRegistry breakers;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final OAuth2TokenFetcher tokenFetcher;

    public ApiTargetCall(TargetEndpointViews views, DestinationGuard guard, ApiTargetBody bodyPort, HttpSend sender) {
        this(views, guard, bodyPort, sender, defaultBreakers());
    }

    static CircuitBreakerRegistry defaultBreakers() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2).build());
    }

    /** T96: one breaker per target endpoint; a half-open breaker lets through at most its trial-call count. */
    public ApiTargetCall(TargetEndpointViews views, DestinationGuard guard, ApiTargetBody bodyPort, HttpSend sender,
            CircuitBreakerRegistry breakers) {
        this(views, guard, bodyPort, sender, breakers, new SimpleMeterRegistry(), ObservationRegistry.NOOP,
                new OAuth2TokenFetcher(sender));
    }

    /** T99 (T-167): every attempt is timed, counted by outcome and observed; breaker state is a gauge per target. */
    public ApiTargetCall(TargetEndpointViews views, DestinationGuard guard, ApiTargetBody bodyPort, HttpSend sender,
            CircuitBreakerRegistry breakers, MeterRegistry meters, ObservationRegistry observations) {
        this(views, guard, bodyPort, sender, breakers, meters, observations, new OAuth2TokenFetcher(sender));
    }

    public ApiTargetCall(TargetEndpointViews views, DestinationGuard guard, ApiTargetBody bodyPort, HttpSend sender,
            CircuitBreakerRegistry breakers, MeterRegistry meters, ObservationRegistry observations,
            OAuth2TokenFetcher tokenFetcher) {
        this.views = views;
        this.guard = guard;
        this.bodyPort = bodyPort;
        this.sender = sender;
        this.breakers = breakers;
        this.meters = meters;
        this.observations = observations;
        this.tokenFetcher = tokenFetcher;
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters);
    }

    @Override
    public void call(Stage2Send send) {
        // the whole attempt is metered from its first step (T-124): a guard refusal is classified PERMANENT,
        // metered like the MQ/Kafka sends (guard at the boundary) and never reaches the socket
        Observation observation = Observation.createNotStarted("api.target.send", observations).start();
        OutcomeClass outcome = OutcomeClass.TRANSIENT;
        long started = System.nanoTime();
        try {
            TargetEndpointView view = views.byEndpointId(send.targetEndpointId()).orElseThrow(() ->
                    new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(), "the send's endpoint "
                            + send.targetEndpointId() + " has no target_endpoint row for the bound tenant - the"
                            + " route resolution and the endpoint are out of step; nothing sent"));
            ApiTargetBody.ApiWireBody body = bodyPort.bodyFor(send).orElseThrow(() ->
                    new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(), "no transform result exists"
                            + " for delivery " + send.deliveryId() + " - the pipeline sent stage 2 without one;"
                            + " the transform lane produces it"));
            CheckedDestination destination;
            try {
                destination = guard.check(view.baseUri());
            } catch (DestinationRefusedException refused) {
                throw new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(), "the destination of "
                        + origin(view.baseUri()) + " is refused (" + refused.refusal() + "): nothing is sent to it");
            } catch (UnknownHostException unresolved) {
                throw new TargetFaultException.TransientFaultException("the allow-listed host of "
                        + origin(view.baseUri()) + " resolves to nothing (the address's fault, not the destination's)");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", body.contentType());
            headers.put("Idempotency-Key", send.deliveryId().toString());
            String traceparent = io.integrationplatform.ingest.port.CorrelationContext.currentTraceparent();
            if (traceparent != null) {
                headers.put("traceparent", traceparent);
            }
            applyAuth(view, headers);
            attempt(send, view, destination, headers, body);
            outcome = OutcomeClass.SUCCESS;
        } catch (TargetFaultException failed) {
            outcome = failed.outcomeClass();
            throw failed;
        } finally {
            // tags are the target type and the outcome class only - never the payload, URI or a credential
            String tag = outcome.name();
            Timer.builder("api.target.send").tag("target_type", "API").tag("outcome", tag).register(meters)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            Counter.builder("api.target.outcome").tag("target_type", "API").tag("outcome", tag).register(meters)
                    .increment();
            observation.lowCardinalityKeyValue("outcome", tag).stop();
        }
    }

    private void attempt(Stage2Send send, TargetEndpointView view, CheckedDestination destination,
            Map<String, String> headers, ApiTargetBody.ApiWireBody body) {
        CircuitBreaker breaker = io.integrationplatform.routing.stage2.BreakerOpenWait.watch(breakers.circuitBreaker(send.targetEndpointId().toString()));
        if (!breaker.tryAcquirePermission()) {
            throw TargetFaultException.CircuitOpenException.of(breaker, send.targetEndpointId());
        }
        long started = System.nanoTime();
        try {
            sendThrough(send, view, destination, headers, body);
            breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        } catch (TargetFaultException failed) {
            // only a target that may be down counts against its breaker; a PERMANENT answer is the target working
            if (failed.outcomeClass() == OutcomeClass.TRANSIENT || failed.outcomeClass() == OutcomeClass.AMBIGUOUS) {
                breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, failed);
            } else {
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            }
            throw failed;
        }
    }

    private void sendThrough(Stage2Send send, TargetEndpointView view, CheckedDestination destination,
            Map<String, String> headers, ApiTargetBody.ApiWireBody body) {
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

    /**
     * Applies authentication headers based on the endpoint's auth method and the opened credentials from the
     * thread-local holder. NO_AUTH adds nothing. A credential-based method with no opened credentials (the
     * credential slice is off) raises TRANSIENT so the delivery waits.
     */
    private void applyAuth(TargetEndpointView view, Map<String, String> headers) {
        String method = view.authMethod();
        if ("NO_AUTH".equals(method)) {
            return;
        }
        OpenedCredentials credentials = TargetCallCredentialHolder.get();
        if (credentials == null) {
            throw new TargetFaultException.TransientFaultException("the endpoint's auth method '"
                    + method + "' requires credentials but the credential gate is not active; the delivery waits");
        }
        switch (method) {
            case "BASIC" -> {
                String username = requireIdentity(view, "BASIC");
                char[] password = requireCredential(credentials, "BASIC_PASSWORD", "BASIC");
                byte[] encoded = Base64.getEncoder().encode(
                        (username + ":" + new String(password)).getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + new String(encoded, StandardCharsets.UTF_8));
            }
            case "BEARER_TOKEN" -> {
                char[] token = requireCredential(credentials, "BEARER_TOKEN", "BEARER_TOKEN");
                headers.put("Authorization", headerValue("Bearer " + new String(token)));
            }
            case "API_KEY" -> {
                String headerName = requireIdentity(view, "API_KEY");
                if (ApiKeyHeaderName.problem(headerName).isPresent()) {
                    // row 510: the name is tenant-typed and goes on the wire verbatim - never a line break, never a
                    // header the platform sets (Idempotency-Key, Content-Type, Host, Authorization ...)
                    throw new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(),
                            "the endpoint's API_KEY header name is not allowed; correct it in the endpoint's auth"
                            + " settings");
                }
                char[] key = requireCredential(credentials, "API_KEY", "API_KEY");
                headers.put(headerName, headerValue(new String(key)));
            }
            case "OAUTH2_CLIENT_CREDENTIALS" -> {
                String clientId = requireIdentity(view, "OAUTH2_CLIENT_CREDENTIALS");
                String tokenUrl = view.authTokenUrl();
                if (tokenUrl == null || tokenUrl.isBlank()) {
                    throw new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(),
                            "the endpoint's OAUTH2_CLIENT_CREDENTIALS method requires auth_token_url but none is"
                            + " configured; the configuration must be completed before deliveries can proceed");
                }
                // row 510: the token URL receives the sealed client secret, so the destination guard speaks first
                // (BEFORE the secret is opened) and the exchange dials the address it pinned
                CheckedDestination tokenEndpoint = guardTokenUrl(tokenUrl);
                char[] clientSecret = requireCredential(credentials, "OAUTH_CLIENT_SECRET",
                        "OAUTH2_CLIENT_CREDENTIALS");
                try {
                    String accessToken = tokenFetcher.fetchToken(tokenEndpoint, clientId, clientSecret);
                    headers.put("Authorization", headerValue("Bearer " + accessToken));
                } catch (OAuth2TokenFetcher.OAuth2TokenException tokenFailed) {
                    if (tokenFailed.isTransient()) {
                        throw new TargetFaultException.TransientFaultException(
                                "OAuth2 token exchange failed (transient): " + tokenFailed.getMessage());
                    }
                    throw new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(),
                            "OAuth2 token exchange failed (permanent): " + tokenFailed.getMessage());
                }
            }
            default -> throw new TargetFaultException.TransientFaultException("the endpoint's auth method '"
                    + method + "' is not yet supported; the delivery waits");
        }
    }

    /**
     * Row 510: the token URL passes the destination guard like the base URL (TLS required, plain http only for the
     * showcase host). The fault text is fixed - no host, port or refusal reason - so it cannot probe the network.
     */
    private CheckedDestination guardTokenUrl(String tokenUrl) {
        java.net.URI uri;
        try {
            uri = new java.net.URI(tokenUrl.strip());
        } catch (java.net.URISyntaxException malformed) {
            throw new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(),
                    "the endpoint's token URL is not a valid address; nothing is sent to it");
        }
        try {
            return guard.checkSecretReceiver(uri);
        } catch (DestinationRefusedException refused) {
            throw new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(),
                    "the endpoint's token URL is refused by the destination policy: nothing is sent to it");
        } catch (UnknownHostException unresolved) {
            throw new TargetFaultException.TransientFaultException(
                    "the endpoint's token URL cannot be resolved right now; the delivery waits");
        }
    }

    /** A header value that came from a secret or from a remote answer must never carry a line break (header injection). */
    private static String headerValue(String value) {
        if (ApiKeyHeaderName.hasLineBreak(value)) {
            throw new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(),
                    "an authentication value contains a line break and is not sent");
        }
        return value;
    }

    private static String requireIdentity(TargetEndpointView view, String authMethod) {
        String identity = view.authIdentity();
        if (identity == null || identity.isBlank()) {
            throw new TargetFaultException(OutcomeClassifier.ofDestinationRefusal(),
                    "the endpoint's " + authMethod + " auth requires auth_identity but none is configured");
        }
        return identity;
    }

    private static char[] requireCredential(OpenedCredentials credentials, String field, String authMethod) {
        return credentials.value(field).orElseThrow(() -> new TargetFaultException(
                OutcomeClassifier.ofDestinationRefusal(),
                "the endpoint's " + authMethod + " auth requires a sealed " + field + " credential but none"
                + " is stored; save the credential through the target credentials admin API"));
    }

    /** F-4 (CWE-532): scheme, host and port only - the stored path and query are tenant-controlled and never logged. */
    private static String origin(java.net.URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }
}
