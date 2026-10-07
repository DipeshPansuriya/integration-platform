package io.integrationplatform.tenancy.internal.web;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * R13 (documents/rule.md:47) and ADR-007 Decision 4: the HTTP client of every identity-provider call - issuer
 * discovery, the JWKS fetch, the console's token endpoint (code exchange, refresh; E4 T-097), the Admin API later.
 * Every client built on one {@link MeterRegistry} shares ONE breaker and retry ({@link #restTemplate}), so the
 * application has one {@value #NAME} breaker however many clients it builds. Each call runs through, outermost first:
 *
 * <ol>
 *   <li><b>metrics and a trace span</b> - a Micrometer observation {@value #OBSERVATION} with low-cardinality tags
 *       {@code operation} (discovery, jwks) and {@code outcome}; Resilience4j retry and breaker meters tagged
 *       {@code name=identity-provider};</li>
 *   <li><b>one circuit breaker</b> {@value #NAME} for the whole provider: after the configured number of consecutive
 *       failed calls it refuses every call without contacting the provider until a half-open trial succeeds;</li>
 *   <li><b>retry of transient failures only</b> - refused connection, connect timeout, and for idempotent reads (GET,
 *       HEAD) a 429, 502, 503 or 504 answer - with exponential random backoff and a cap. A 4xx answer (401 and 403
 *       included), a read timeout of a sent request and any answer to a non-idempotent call (a token POST: the
 *       provider may have used the authorization code) are never retried;</li>
 *   <li><b>the timeout</b> - 2 s to connect and 2 s to answer ({@link TenancySecurityConfiguration#JWKS_TIMEOUT}).</li>
 * </ol>
 *
 * The fifth control, the <b>fallback</b>, is the {@link OutageTolerantKeySetCache}: the last fetched keys keep
 * validating while the provider is unreachable.
 */
final class IdentityProviderClient implements ClientHttpRequestInterceptor {

    static final String NAME = "identity-provider";
    static final String OBSERVATION = "identity.provider.call";

    private static final List<Integer> TRANSIENT_STATUSES = List.of(429, 502, 503, 504);

    /** One breaker and retry per meter registry - per application context (weak: a closed context's go away). */
    private static final Map<MeterRegistry, Resilience> SHARED = new WeakHashMap<>();

    private final CircuitBreaker breaker;
    private final Retry retry;
    private final ObservationRegistry observations;

    /** The breaker and retry of one application (the first settings given for its meter registry win). */
    private record Resilience(CircuitBreaker breaker, Retry retry) {
    }

    private IdentityProviderClient(Resilience resilience, ObservationRegistry observations) {
        this.breaker = resilience.breaker();
        this.retry = resilience.retry();
        this.observations = observations;
    }

    private static Resilience resilience(IdentityProviderSettings settings, MeterRegistry meters) {
        synchronized (SHARED) {
            return SHARED.computeIfAbsent(meters, registry -> create(settings, registry));
        }
    }

    private static Resilience create(IdentityProviderSettings settings, MeterRegistry meters) {
        int failures = settings.breakerFailureThreshold();
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(failures)
                .minimumNumberOfCalls(failures)
                .failureRateThreshold(100) // every call in the window failed = that many consecutive failures
                .waitDurationInOpenState(settings.breakerWaitInOpen())
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(settings.retryMaxAttempts())
                .intervalFunction(backoff(settings))
                .retryOnException(IdentityProviderClient::isTransient)
                .build());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters);
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meters);
        return new Resilience(breakers.circuitBreaker(NAME), retries.retry(NAME));
    }

    /** A client for any identity-provider call: the five controls around the 2 s timeout, the application's breaker. */
    static RestTemplate restTemplate(IdentityProviderSettings settings, MeterRegistry meters,
            ObservationRegistry observations) {
        // The JDK client tells a connect timeout (retryable) from a read timeout (never retried) by type.
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TenancySecurityConfiguration.JWKS_TIMEOUT).build());
        requests.setReadTimeout(TenancySecurityConfiguration.JWKS_TIMEOUT);
        RestTemplate rest = new RestTemplate(requests);
        rest.getInterceptors().add(new IdentityProviderClient(resilience(settings, meters), observations));
        return rest;
    }

    /** Exponential backoff with jitter (+/- the randomization factor) and a cap on every wait. */
    static IntervalFunction backoff(IdentityProviderSettings settings) {
        return IntervalFunction.ofExponentialRandomBackoff(settings.retryInitialBackoff(), settings.retryMultiplier(),
                settings.retryRandomization(), settings.retryMaxBackoff());
    }

    /** Retry only what never reached the provider or what it declared temporary; never a read timeout. */
    static boolean isTransient(Throwable failure) {
        return failure instanceof ConnectException
                || failure instanceof HttpConnectTimeoutException
                || failure instanceof TransientStatus;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Observation observation = Observation.createNotStarted(OBSERVATION, observations)
                .lowCardinalityKeyValue("operation", operation(request.getURI()))
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            ClientHttpResponse response = breaker.executeCheckedSupplier(() -> retry.executeCheckedSupplier(
                    () -> failIfTransient(request.getMethod(), execution.execute(request, body))));
            observation.lowCardinalityKeyValue("outcome", outcome(response.getStatusCode()));
            return response;
        } catch (CallNotPermittedException open) {
            throw failed(observation, "breaker_open", new IOException("The identity provider circuit is open", open));
        } catch (IOException failure) {
            throw failed(observation, "error", failure);
        } catch (Throwable failure) {
            throw failed(observation, "error", new IOException(failure.getMessage(), failure));
        } finally {
            observation.stop();
        }
    }

    private static ClientHttpResponse failIfTransient(HttpMethod method, ClientHttpResponse response)
            throws IOException {
        int status = response.getStatusCode().value();
        if (TRANSIENT_STATUSES.contains(status)) {
            response.close();
            if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) {
                throw new TransientStatus(status);
            }
            throw new UnavailableStatus(status); // counted by the breaker, never retried
        }
        return response;
    }

    private static IOException failed(Observation observation, String outcome, IOException failure) {
        observation.lowCardinalityKeyValue("outcome", outcome);
        observation.error(failure);
        return failure;
    }

    private static String operation(URI uri) {
        String path = uri.getPath();
        if (path.contains("/.well-known/")) {
            return "discovery";
        }
        return path.endsWith("/token") ? "token" : "jwks";
    }

    private static String outcome(HttpStatusCode status) {
        return status.is2xxSuccessful() ? "success" : status.is4xxClientError() ? "client_error" : "other";
    }

    /** A 429 or 5xx gateway answer to an idempotent read: the provider said "try again later". */
    static final class TransientStatus extends IOException {
        TransientStatus(int status) {
            super("Identity provider answered " + status);
        }
    }

    /** The same answer to a non-idempotent call: a failure for the breaker, never retried. */
    static final class UnavailableStatus extends IOException {
        UnavailableStatus(int status) {
            super("Identity provider answered " + status);
        }
    }
}
