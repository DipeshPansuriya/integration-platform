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
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * R13 (documents/rule.md:47) and ADR-007 Decision 4: the HTTP client of every identity-provider call - issuer
 * discovery and the JWKS fetch today, the Admin API later. Each call runs through, outermost first:
 *
 * <ol>
 *   <li><b>metrics and a trace span</b> - a Micrometer observation {@value #OBSERVATION} with low-cardinality tags
 *       {@code operation} (discovery, jwks) and {@code outcome}; Resilience4j retry and breaker meters tagged
 *       {@code name=identity-provider};</li>
 *   <li><b>one circuit breaker</b> {@value #NAME} for the whole provider: after the configured number of consecutive
 *       failed calls it refuses every call without contacting the provider until a half-open trial succeeds;</li>
 *   <li><b>retry of transient failures only</b> - refused connection, connect timeout, 429, 502, 503, 504 - with
 *       exponential random backoff and a cap. A 4xx answer (401 and 403 included) and a read timeout of a sent request
 *       are never retried;</li>
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

    private final CircuitBreaker breaker;
    private final Retry retry;
    private final ObservationRegistry observations;

    private IdentityProviderClient(IdentityProviderSettings settings, MeterRegistry meters,
            ObservationRegistry observations) {
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
        this.breaker = breakers.circuitBreaker(NAME);
        this.retry = retries.retry(NAME);
        this.observations = observations;
    }

    /** The client for discovery and JWKS: the five controls around the 2 s timeout. */
    static RestTemplate restTemplate(IdentityProviderSettings settings, MeterRegistry meters,
            ObservationRegistry observations) {
        // The JDK client tells a connect timeout (retryable) from a read timeout (never retried) by type.
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TenancySecurityConfiguration.JWKS_TIMEOUT).build());
        requests.setReadTimeout(TenancySecurityConfiguration.JWKS_TIMEOUT);
        RestTemplate rest = new RestTemplate(requests);
        rest.getInterceptors().add(new IdentityProviderClient(settings, meters, observations));
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
                    () -> failIfTransient(execution.execute(request, body))));
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

    private static ClientHttpResponse failIfTransient(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (TRANSIENT_STATUSES.contains(status)) {
            response.close();
            throw new TransientStatus(status);
        }
        return response;
    }

    private static IOException failed(Observation observation, String outcome, IOException failure) {
        observation.lowCardinalityKeyValue("outcome", outcome);
        observation.error(failure);
        return failure;
    }

    private static String operation(URI uri) {
        return uri.getPath().contains("/.well-known/") ? "discovery" : "jwks";
    }

    private static String outcome(HttpStatusCode status) {
        return status.is2xxSuccessful() ? "success" : status.is4xxClientError() ? "client_error" : "other";
    }

    /** A 429 or 5xx gateway answer: the provider said "try again later". */
    static final class TransientStatus extends IOException {
        TransientStatus(int status) {
            super("Identity provider answered " + status);
        }
    }
}
