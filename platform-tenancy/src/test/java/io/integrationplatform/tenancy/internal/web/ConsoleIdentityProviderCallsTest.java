package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * E4 (T-097), R13 and ADR-007 Decision 4 for the console's token-endpoint calls (code exchange, refresh): they go
 * through the same {@link IdentityProviderClient} as discovery and JWKS - one {@code identity-provider} breaker, the
 * 2 s timeout, metrics and a span - and, being non-idempotent POSTs, a 429/5xx answer is never retried ("retry of
 * transient failures of idempotent reads only"). A connection that never reached the provider may still be retried.
 */
class ConsoleIdentityProviderCallsTest {

    private HttpServer server;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger readCalls = new AtomicInteger();
    private volatile int status = 503;
    private SimpleMeterRegistry meters;
    private TestObservationRegistry observations;

    @BeforeEach
    void start() throws IOException {
        meters = new SimpleMeterRegistry();
        observations = TestObservationRegistry.create();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/realms/platform/protocol/openid-connect/token", exchange -> {
            tokenCalls.incrementAndGet();
            answer(exchange, status);
        });
        server.createContext("/realms/platform/protocol/openid-connect/certs", exchange -> {
            readCalls.incrementAndGet();
            answer(exchange, status);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aTokenEndpointPostAnsweredWithA5xxIsNotRetried() {
        RestTemplate rest = IdentityProviderClient.restTemplate(settings(5), meters, observations);

        assertThatThrownBy(() -> rest.postForEntity(url("/token"), "grant_type=refresh_token", String.class))
                .isInstanceOf(ResourceAccessException.class);

        assertThat(tokenCalls).as("a POST that reached the provider is sent once").hasValue(1);
    }

    @Test
    void anIdempotentReadAnsweredWithA5xxIsStillRetried() {
        RestTemplate rest = IdentityProviderClient.restTemplate(settings(5), meters, observations);

        assertThatThrownBy(() -> rest.getForEntity(url("/certs"), String.class))
                .isInstanceOf(ResourceAccessException.class);

        assertThat(readCalls).as("GET retried up to the attempt limit").hasValue(3);
    }

    @Test
    void everyIdentityProviderClientOfTheApplicationSharesTheOneBreaker() {
        RestTemplate keys = IdentityProviderClient.restTemplate(settings(2), meters, observations);
        RestTemplate console = IdentityProviderClient.restTemplate(settings(2), meters, observations);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> keys.getForEntity(url("/certs"), String.class))
                    .isInstanceOf(ResourceAccessException.class);
        }

        assertThatThrownBy(() -> console.postForEntity(url("/token"), "grant_type=authorization_code", String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseMessage("CircuitBreaker 'identity-provider' is OPEN and does not permit further calls");
        assertThat(tokenCalls).as("the open breaker refuses the token call without contacting the provider")
                .hasValue(0);
    }

    @Test
    void aTokenCallIsObservedAsTheTokenOperation() {
        status = 200;
        RestTemplate rest = IdentityProviderClient.restTemplate(settings(5), meters, observations);

        rest.postForEntity(url("/token"), "grant_type=authorization_code", String.class);

        TestObservationRegistryAssert.assertThat(observations)
                .hasAnObservation(o -> o.hasNameEqualTo(IdentityProviderClient.OBSERVATION)
                        .hasLowCardinalityKeyValue("operation", "token")
                        .hasLowCardinalityKeyValue("outcome", "success"));
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static IdentityProviderSettings settings(int breakerFailureThreshold) {
        return new IdentityProviderSettings(3, Duration.ofMillis(20), 2.0, 0.5, Duration.ofMillis(60),
                breakerFailureThreshold, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofHours(1));
    }

    private String url(String endpoint) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/platform/protocol/openid-connect"
                + endpoint;
    }

    private static void answer(com.sun.net.httpserver.HttpExchange exchange, int status) throws IOException {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
