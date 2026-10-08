package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.OutboundHttpCheck;
import io.integrationplatform.config.destination.OutboundHttpCheck.Outcome;
import io.integrationplatform.config.destination.OutboundHttpCheck.Result;
import io.integrationplatform.config.destination.SocketDialer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-104, R13 (documents/rule.md:47): one fault test per control of the test-connection call, against WireMock behind
 * the guard (the allowed destination of E5-F1-S2) - timeout, transient-only retry with backoff and cap, one breaker per
 * destination, the fallback answer, metrics and the span - and the redirect case on a real HTTP server: a WireMock
 * stub answering 302 to a loopback or private address.
 */
@Tag("container")
class OutboundHttpCheckFaultContainerTest {

    private static final WireMockDestination WIREMOCK = new WireMockDestination();

    private SimpleMeterRegistry meters;
    private final List<String> observations = new CopyOnWriteArrayList<>();
    private ObservationRegistry observationRegistry;

    @BeforeAll
    static void startWireMock() {
        WIREMOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void freshStubsAndRegistries() {
        WIREMOCK.reset();
        WIREMOCK.clearDialled();
        WIREMOCK.resolve("loop.e5.test", WireMockDestination.literal("127.0.0.1"));
        WIREMOCK.resolve("lan.e5.test", WireMockDestination.literal("172.16.4.4"));
        meters = new SimpleMeterRegistry();
        observations.clear();
        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                observations.add(context.getName() + " outcome="
                        + context.getLowCardinalityKeyValue("outcome").getValue());
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
    }

    /** Small, fast settings so each control shows within a test: 3 attempts, breaker opens after 3 failures. */
    private static OutboundHttpCheck.Settings fastSettings() {
        return new OutboundHttpCheck.Settings(Duration.ofSeconds(2), Duration.ofSeconds(2), 3, 3,
                Duration.ofMillis(50), 2.0, 0.5, Duration.ofMillis(200), 3, Duration.ofMinutes(5));
    }

    private OutboundHttpCheck check(SocketDialer dialer) {
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("*.e5.test")), WIREMOCK.resolver());
        return new OutboundHttpCheck(guard, dialer, fastSettings(), meters, observationRegistry);
    }

    private static URI at(String path) {
        return URI.create(WireMockDestination.address(path));
    }

    private double count(String outcome, String reason) {
        Counter counter = meters.find(OutboundHttpCheck.METRIC).tag("outcome", outcome).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("Success: WireMock answers, the result shows success and the response time")
    void anAllowedDestinationAnswers() {
        WIREMOCK.stubGet("/health", 200, "\"body\":\"ok\"");

        Result result = check(WIREMOCK.dialer()).check(at("/health"), false, null);

        assertThat(result.outcome()).as(result.toString()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.responseTimeMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(WIREMOCK.dialled()).as("the pinned documentation address is dialled")
                .allMatch(address -> address.getAddress().equals(WireMockDestination.PINNED));
        assertThat(WIREMOCK.requestCount()).isEqualTo(1);
    }

    @Test
    @Tag("negative")
    @DisplayName("R13 timeout: an answer slower than the timeout fails cleanly as TIMEOUT and is not retried")
    void timeout() {
        WIREMOCK.stubGet("/slow", 200, "\"fixedDelayMilliseconds\":3000");
        long started = System.nanoTime();

        Result result = check(WIREMOCK.dialer()).check(at("/slow"), false, Duration.ofMillis(400));

        long tookMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.reason()).isEqualTo("TIMEOUT");
        assertThat(tookMs).as("bounded by the timeout, not by the 3 s delay").isLessThan(2500L);
        assertThat(WIREMOCK.requestCount()).as("a read timeout of a sent request is never retried").isEqualTo(1);
    }

    @Test
    @Tag("negative")
    @DisplayName("R13 retry: a 503 is transient - retried with backoff up to the attempt cap, then a clean failure")
    void transientAnswersAreRetriedToTheCap() {
        WIREMOCK.stubGet("/busy", 503, "");

        Result result = check(WIREMOCK.dialer()).check(at("/busy"), false, null);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.reason()).isEqualTo("SERVER_ERROR");
        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(WIREMOCK.requestCount()).as("max attempts").isEqualTo(3);
    }

    @Test
    @Tag("negative")
    @DisplayName("R13 retry is transient-only: a 4xx is an answer (reachable) and is never retried")
    void aClientErrorIsNotRetried() {
        WIREMOCK.stubGet("/auth", 401, "");

        Result result = check(WIREMOCK.dialer()).check(at("/auth"), false, null);

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.httpStatus()).isEqualTo(401);
        assertThat(WIREMOCK.requestCount()).isEqualTo(1);
    }

    @Test
    @Tag("negative")
    @DisplayName("R13 breaker: after the failure threshold the destination's breaker opens and no call is made")
    void theBreakerOpensPerDestination() {
        WIREMOCK.stubGet("/down", 500, "");
        OutboundHttpCheck check = check(WIREMOCK.dialer());
        for (int i = 0; i < 3; i++) {
            assertThat(check.check(at("/down"), false, null).reason()).isEqualTo("SERVER_ERROR");
        }
        int callsBefore = WIREMOCK.requestCount();

        Result result = check.check(at("/down"), false, null);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.reason()).isEqualTo("CIRCUIT_OPEN");
        assertThat(WIREMOCK.requestCount()).as("an open breaker makes no call").isEqualTo(callsBefore);
        // one breaker PER destination: another destination's breaker is still closed
        WIREMOCK.resolve("other.e5.test", WireMockDestination.PINNED);
        Result other = check.check(URI.create("http://other.e5.test:8080/down"), false, null);
        assertThat(other.reason()).isEqualTo("SERVER_ERROR");
    }

    @Test
    @Tag("negative")
    @DisplayName("R13 fallback: an unreachable destination answers a clean FAILED result, never an exception")
    void fallbackOnAnUnreachableDestination() {
        Result result = check(WireMockDestination.refusingDialer()).check(at("/any"), false, null);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.reason()).isEqualTo("UNREACHABLE");
        assertThat(result.httpStatus()).isNull();
    }

    @Test
    @DisplayName("R13 metrics and span: every check is counted by outcome, observed, and the breaker is metered")
    void metricsAndSpanAreRecorded() {
        WIREMOCK.stubGet("/ok", 200, "");
        OutboundHttpCheck check = check(WIREMOCK.dialer());

        check.check(at("/ok"), false, null);
        check.check(URI.create("http://loop.e5.test:8080/"), false, null);

        assertThat(count("SUCCESS", "NONE")).isEqualTo(1.0);
        assertThat(count("REFUSED", "REFUSED_ADDRESS")).isEqualTo(1.0);
        assertThat(meters.find("resilience4j.circuitbreaker.state").gauges()).as("breaker state gauges").isNotEmpty();
        assertThat(meters.find("resilience4j.retry.calls").meters()).as("retry meters").isNotEmpty();
        assertThat(observations).contains(OutboundHttpCheck.OBSERVATION + " outcome=SUCCESS",
                OutboundHttpCheck.OBSERVATION + " outcome=REFUSED");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A WireMock 302 to a loopback address is refused on the hop: the inside is never dialled")
    void aRedirectToLoopbackIsRefused() {
        WIREMOCK.stubRedirect("/hop", 302, "http://127.0.0.1:8080/admin");

        Result result = check(WIREMOCK.dialer()).check(at("/hop"), true, null);

        assertThat(result.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(result.reason()).isEqualTo("REFUSED_ADDRESS");
        assertThat(WIREMOCK.dialled()).as("only the first hop is dialled").hasSize(1);
        assertThat(WIREMOCK.requestCount()).isEqualTo(1);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A WireMock 302 to a name resolving to a private range is refused on the hop")
    void aRedirectToAPrivateNameIsRefused() {
        WIREMOCK.stubRedirect("/hop2", 302, "http://lan.e5.test/");

        Result result = check(WIREMOCK.dialer()).check(at("/hop2"), true, null);

        assertThat(result.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(WIREMOCK.dialled()).hasSize(1);
    }
}
