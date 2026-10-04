package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.web.TenantFixtures.ALICE;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.AUDIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.core.IntervalFunction;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * R13 (documents/rule.md:47) and ADR-007 Decision 4: every call to the identity provider - issuer discovery and the
 * JWKS fetch - carries five controls: the 2 s timeout ({@link JwksFetchTimeoutTest}), transient-only retry with
 * exponential backoff, jitter and a cap, one circuit breaker {@code identity-provider}, a fallback (the last fetched
 * keys keep validating) and metrics plus a trace span. One fault test per control; each fails when its control is
 * removed. A provider failure is always a refusal the bearer layer answers with 401 ({@link BadJwtException}), never a
 * server error and never an acceptance.
 */
class IdentityProviderResilienceTest {

    private static final Set<SignatureAlgorithm> ALGORITHMS = Set.of(SignatureAlgorithm.RS256, SignatureAlgorithm.ES256);

    private LocalIssuer issuer;
    private SimpleMeterRegistry meters;
    private TestObservationRegistry observations;

    @BeforeEach
    void start() {
        issuer = LocalIssuer.start();
        meters = new SimpleMeterRegistry();
        observations = TestObservationRegistry.create();
    }

    @AfterEach
    void stop() {
        issuer.close();
    }

    /** Short waits so the suite stays fast; the production values are in {@link IdentityProviderSettings}. */
    private static IdentityProviderSettings fast(int breakerFailureThreshold, Duration breakerWaitInOpen,
            Duration keySetTtl) {
        return new IdentityProviderSettings(3, Duration.ofMillis(60), 2.0, 0.5, Duration.ofMillis(300),
                breakerFailureThreshold, breakerWaitInOpen, keySetTtl, Duration.ofHours(1));
    }

    private static IdentityProviderSettings fast() {
        return fast(5, Duration.ofSeconds(30), Duration.ofMinutes(5));
    }

    private JwtDecoder decoder(String issuerUri, IdentityProviderSettings settings) {
        return TenancySecurityConfiguration.jwtDecoder(issuerUri, List.of(AUDIENCE), ALGORITHMS, settings, meters,
                observations);
    }

    private JwtDecoder decoder(IdentityProviderSettings settings) {
        return decoder(issuer.issuer(), settings);
    }

    private String token() {
        return issuer.rs256(issuer.claims(ALICE, c -> { }));
    }

    private double retryCalls(String kind) {
        return meters.find("resilience4j.retry.calls").tag("name", IdentityProviderClient.NAME).tag("kind", kind)
                .functionCounters().stream().mapToDouble(c -> c.count()).sum();
    }

    // ---- 1. Retry of transient failures, with exponential backoff, jitter and a cap -------------------------------

    @Test
    void transientKeySetFailuresAreRetriedWithBackoffAndTheTokenIsAccepted() {
        issuer.scriptKeys(503, 503);
        JwtDecoder decoder = decoder(fast());

        assertThat(decoder.decode(token()).getSubject()).isEqualTo(ALICE);
        assertThat(issuer.keyRequests()).as("503, 503, then 200 - three JWKS requests").isEqualTo(3);
        // Waits are drawn from 60 ms +/- 50 % and then 120 ms +/- 50 %: never back-to-back.
        assertThat(issuer.keyRequestGaps()).allSatisfy(gap -> assertThat(gap).isGreaterThan(Duration.ofMillis(20)));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 502, 503, 504})
    void everyTransientStatusIsRetried(int status) {
        issuer.scriptKeys(status);

        assertThat(decoder(fast()).decode(token()).getSubject()).isEqualTo(ALICE);
        assertThat(issuer.keyRequests()).isEqualTo(2);
    }

    @Test
    void aRefusedConnectionIsRetried() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        JwtDecoder decoder = decoder("http://127.0.0.1:" + closedPort + "/realms/platform", fast());

        assertThatThrownBy(() -> decoder.decode(token())).isInstanceOf(BadJwtException.class);
        assertThat(retryCalls("failed_with_retry")).as("the refused discovery call was retried").isEqualTo(1.0);
    }

    @Test
    void onlyConnectFailuresAndTransientStatusesAreRetryable() {
        assertThat(IdentityProviderClient.isTransient(new ConnectException("refused"))).isTrue();
        assertThat(IdentityProviderClient.isTransient(new HttpConnectTimeoutException("connect timed out"))).isTrue();
        // A read timeout means the request was sent: never retried (R13).
        assertThat(IdentityProviderClient.isTransient(new HttpTimeoutException("request timed out"))).isFalse();
        assertThat(IdentityProviderClient.isTransient(new SocketTimeoutException("Read timed out"))).isFalse();
        assertThat(IdentityProviderClient.isTransient(new IOException("other"))).isFalse();
        assertThat(IdentityProviderClient.isTransient(new IllegalStateException("bug"))).isFalse();
    }

    @Test
    void theDefaultBackoffIsExponentialRandomWithACap() {
        IdentityProviderSettings defaults = IdentityProviderSettings.defaults();
        assertThat(defaults.retryMaxAttempts()).isEqualTo(3);
        assertThat(defaults.retryInitialBackoff()).isEqualTo(Duration.ofMillis(200));
        assertThat(defaults.retryMaxBackoff()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.retryRandomization()).isEqualTo(0.5);

        IntervalFunction backoff = IdentityProviderClient.backoff(defaults);
        Set<Long> firstWaits = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            long first = backoff.apply(1);
            assertThat(first).as("attempt 1: 200 ms +/- 50 %").isBetween(100L, 300L);
            firstWaits.add(first);
        }
        assertThat(firstWaits).as("jitter: the waits differ").hasSizeGreaterThan(5);
        double meanFirst = IntStream.range(0, 200).mapToLong(i -> backoff.apply(1)).average().orElseThrow();
        double meanThird = IntStream.range(0, 200).mapToLong(i -> backoff.apply(3)).average().orElseThrow();
        assertThat(meanThird).as("exponential growth").isGreaterThan(meanFirst * 2.5);
        for (int attempt = 1; attempt <= 12; attempt++) {
            assertThat(backoff.apply(attempt)).as("cap at attempt %d", attempt).isLessThanOrEqualTo(2000L);
        }
    }

    // ---- 2. Never retry 401/403, other 4xx or a timeout of a sent request ------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void aClientErrorFromTheKeySetIsNotRetriedAndNothingIsAccepted(int status) {
        issuer.keysStatus(status);

        assertThatThrownBy(() -> decoder(fast()).decode(token())).isInstanceOf(BadJwtException.class);
        assertThat(issuer.keyRequests()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void aClientErrorFromDiscoveryIsNotRetriedAndNothingIsAccepted(int status) {
        issuer.discoveryStatus(status);

        assertThatThrownBy(() -> decoder(fast()).decode(token())).isInstanceOf(BadJwtException.class);
        // Spring tries the OIDC and then the OAuth metadata path; each answered once (only the OIDC path is served).
        assertThat(issuer.discoveryRequests()).isEqualTo(1);
        assertThat(issuer.keyRequests()).isZero();
    }

    @Test
    void aSlowKeySetIsNotRetriedAfterItsTimeout() {
        issuer.delayKeys(Duration.ofSeconds(5));

        assertThatThrownBy(() -> decoder(fast()).decode(token())).isInstanceOf(BadJwtException.class);
        assertThat(issuer.keyRequests()).isEqualTo(1);
    }

    // ---- 3. One circuit breaker 'identity-provider' for discovery and JWKS ----------------------------------------

    @Test
    void anOpenBreakerMakesNoProviderCallAndRefusesFastThenAHalfOpenTrialClosesIt() throws InterruptedException {
        issuer.keysStatus(503);
        JwtDecoder decoder = decoder(fast(2, Duration.ofMillis(800), Duration.ofMinutes(5)));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> decoder.decode(token())).isInstanceOf(BadJwtException.class);
        }
        int keysBefore = issuer.keyRequests();
        int discoveryBefore = issuer.discoveryRequests();

        long started = System.nanoTime();
        assertThatThrownBy(() -> decoder.decode(token())).isInstanceOf(BadJwtException.class);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(issuer.keyRequests()).as("open breaker: no JWKS call").isEqualTo(keysBefore);
        assertThat(issuer.discoveryRequests()).as("open breaker: no discovery call").isEqualTo(discoveryBefore);
        assertThat(took).as("refused fast").isLessThan(Duration.ofMillis(200));

        Thread.sleep(900);
        issuer.keysStatus(200);
        assertThat(decoder.decode(token()).getSubject()).as("half-open trial reaches the provider and closes it")
                .isEqualTo(ALICE);
        assertThat(issuer.keyRequests()).as("exactly one half-open trial").isEqualTo(keysBefore + 1);
        assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", IdentityProviderClient.NAME)
                .tag("state", "closed").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void discoveryAndKeySetShareOneBreaker() {
        issuer.discoveryStatus(503);
        JwtDecoder decoder = decoder(fast(2, Duration.ofSeconds(30), Duration.ofMinutes(5)));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> decoder.decode(token())).isInstanceOf(BadJwtException.class);
        }
        int before = issuer.discoveryRequests();
        assertThatThrownBy(() -> decoder.decode(token())).isInstanceOf(BadJwtException.class);

        assertThat(issuer.discoveryRequests()).as("open breaker: discovery not called").isEqualTo(before);
        assertThat(meters.find("resilience4j.circuitbreaker.state").tags("name", IdentityProviderClient.NAME)
                .gauges()).as("exactly one breaker, named identity-provider").isNotEmpty();
        assertThat(meters.getMeters().stream().map(Meter::getId)
                .filter(id -> id.getName().startsWith("resilience4j.circuitbreaker"))
                .map(id -> id.getTag("name")).distinct()).containsExactly(IdentityProviderClient.NAME);
    }

    // ---- 4. Fallback: the last fetched keys keep validating while the provider is down ----------------------------

    @Test
    void cachedKeysKeepValidatingNewTokensWhileTheProviderIsDown() throws InterruptedException {
        JwtDecoder decoder = decoder(fast(2, Duration.ofSeconds(30), Duration.ofMillis(200)));
        assertThat(decoder.decode(token()).getSubject()).isEqualTo(ALICE);

        issuer.keysStatus(503);
        Thread.sleep(300); // the key set's time to live has passed

        assertThat(decoder.decode(token()).getSubject()).as("a new token, same key id, provider down")
                .isEqualTo(ALICE);
        assertThat(issuer.keyRequests()).as("the refresh was tried").isGreaterThan(1);

        String unknownKey = issuer.rs256WithUnpublishedKeyId(issuer.claims(ALICE, c -> { }));
        assertThatThrownBy(() -> decoder.decode(unknownKey)).as("an unknown key id is never accepted")
                .isInstanceOf(BadJwtException.class);
        int afterOpen = issuer.keyRequests();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> decoder.decode(unknownKey)).isInstanceOf(BadJwtException.class);
        }
        assertThat(issuer.keyRequests()).as("unknown key ids do not hammer a provider that is down")
                .isEqualTo(afterOpen);
        assertThat(decoder.decode(token()).getSubject()).as("known key still validates").isEqualTo(ALICE);
    }

    @Test
    void aFailedFirstDiscoveryDoesNotPoisonLaterCalls() {
        issuer.scriptDiscovery(503, 503, 503);
        JwtDecoder decoder = decoder(fast());

        assertThatThrownBy(() -> decoder.decode(token())).isInstanceOf(BadJwtException.class);
        assertThat(decoder.decode(token()).getSubject()).as("provider back: discovery runs again")
                .isEqualTo(ALICE);
    }

    // ---- 5. Metrics and a trace span on every call -----------------------------------------------------------------

    @Test
    void everyCallIsObservedAndTheResilienceMetersAreRegistered() {
        assertThat(decoder(fast()).decode(token()).getSubject()).isEqualTo(ALICE);

        TestObservationRegistryAssert.assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo(IdentityProviderClient.OBSERVATION, 2)
                .hasAnObservation(o -> o.hasNameEqualTo(IdentityProviderClient.OBSERVATION)
                        .hasLowCardinalityKeyValue("operation", "discovery")
                        .hasLowCardinalityKeyValue("outcome", "success"))
                .hasAnObservation(o -> o.hasNameEqualTo(IdentityProviderClient.OBSERVATION)
                        .hasLowCardinalityKeyValue("operation", "jwks")
                        .hasLowCardinalityKeyValue("outcome", "success"));
        assertThat(meters.find("resilience4j.circuitbreaker.calls").tag("name", IdentityProviderClient.NAME).meters())
                .isNotEmpty();
        assertThat(meters.find("resilience4j.retry.calls").tag("name", IdentityProviderClient.NAME).meters())
                .isNotEmpty();
    }

    @Test
    void aRefusedCallIsObservedWithItsOutcome() {
        issuer.keysStatus(401);

        assertThatThrownBy(() -> decoder(fast()).decode(token())).isInstanceOf(BadJwtException.class);
        TestObservationRegistryAssert.assertThat(observations)
                .hasAnObservation(o -> o.hasNameEqualTo(IdentityProviderClient.OBSERVATION)
                        .hasLowCardinalityKeyValue("operation", "jwks")
                        .hasLowCardinalityKeyValue("outcome", "client_error"));
    }

    // ---- Settings ------------------------------------------------------------------------------------------------

    @Test
    void settingsBindFromTenancySecurityYamlKeys() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tenancy.identity-provider.breaker-failure-threshold", "7")
                .withProperty("platform.tenancy.identity-provider.key-set-ttl", "10m");

        IdentityProviderSettings settings = IdentityProviderSettings.from(environment);

        assertThat(settings.breakerFailureThreshold()).isEqualTo(7);
        assertThat(settings.keySetTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(settings.retryMaxAttempts()).isEqualTo(3);
    }
}
