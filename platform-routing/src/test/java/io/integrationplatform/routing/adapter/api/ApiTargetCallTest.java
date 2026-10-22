package io.integrationplatform.routing.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.adapter.api.HttpSend.Response;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The API target adapter's unit legs (E10 narrow, T-050, ADR-015): the send goes through the destination guard
 * (reuse - never its own URL policy), every failure is classified ONCE by {@code OutcomeClassifier} and raised as
 * a {@code TargetFaultException} - never a naked status or exception - so the layer-1 route's redelivery follows
 * one register (ADR-010 section 5). The HTTP wire itself is the {@code HttpSend} seam here; the real socket send
 * is the container test's business ({@code ApiTargetCallSocketHttpSendContainerTest}).
 */
class ApiTargetCallTest {

    private static final InetAddress PINNED = address("203.0.113.10");
    private static final UUID DELIVERY = UUID.fromString("dddd0000-0000-4000-8000-000000000001");

    private final List<Call> sent = new ArrayList<>();
    private final ApiTargetBody bodyPort = send -> Optional.of(
            new ApiTargetBody.ApiWireBody("application/json", "{\"x\":1}".getBytes(StandardCharsets.UTF_8)));

    @Test
    @DisplayName("success: the send carries the guard's pinned address, the endpoint's method, timeout and"
            + " Idempotency-Key, and answers normally")
    void successCarriesPinnedAddressEndpointShape() {
        RecordingHttpSend sender = answering(() -> new Response(200, new byte[0]));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of()), sender);

        target.call(send());

        assertThat(sent).hasSize(1);
        Call call = sent.get(0);
        assertThat(call.destination().pinnedAddress()).isEqualTo(PINNED);
        assertThat(call.destination().uri()).isEqualTo(URI.create("http://target.e5.test:8080/api"));
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.headers().get("Idempotency-Key")).contains(DELIVERY.toString());
        assertThat(call.headers().get("Content-Type")).contains("application/json");
        assertThat(call.timeoutMs()).isEqualTo(1500);
        assertThat(call.body()).isEqualTo("{\"x\":1}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("success: the send carries the open journey's W3C traceparent, verbatim from the journey the"
            + " delivery opened - the same source the Kafka leg's header carries")
    void successCarriesOpenJourneyTraceparent() {
        String traceparent = "00-7d3e5f0a1b2c4d6e8f90a1b2c3d4e5f6-0123456789abcdef-01";
        RecordingHttpSend sender = answering(() -> new Response(200, new byte[0]));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of()), sender);
        try (io.integrationplatform.ingest.port.CorrelationContext.Scope journey =
                io.integrationplatform.ingest.port.CorrelationContext.open(traceparent)) {
            target.call(send());
        }
        assertThat(sent.get(0).headers().get("traceparent")).as("the journey's traceparent on the wire")
                .isEqualTo(traceparent);
    }

    @Test
    @DisplayName("the traceparent header is a valid W3C version-00 value when the journey started fresh, and"
            + " absent when no journey is open")
    void traceparentHeaderIsValidW3cOrAbsentWithoutAJourney() {
        assertThat(sent).as("no journey open yet; the first leg has not run").isEmpty();
        RecordingHttpSend sender = answering(() -> new Response(200, new byte[0]));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of()), sender);
        target.call(send());
        assertThat(sent.get(0).headers().get("traceparent")).as("no open journey - no header to carry").isNull();
        try (io.integrationplatform.ingest.port.CorrelationContext.Scope journey =
                io.integrationplatform.ingest.port.CorrelationContext.open(null)) {
            sent.clear();
            target.call(send());
        }
        String carried = sent.get(0).headers().get("traceparent");
        assertThat(carried).as("a fresh journey still carries one valid W3C traceparent").isNotNull()
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
        assertThat(io.integrationplatform.ingest.port.CorrelationContext.correlationIdOf(carried))
                .as("the trace id is the W3C trace id the target's consumer continues").isNotNull();
    }

    @Test
    @DisplayName("a refused destination is PERMANENT and nothing is sent")
    void refusedDestinationIsPermanent() {
        RecordingHttpSend sender = answering(() -> new Response(200, new byte[0]));
        ApiTargetCall target = new ApiTargetCall(views(view("http://target.e5.test:8080/api", "POST", 1500,
                "NO_AUTH", false, Map.of())), new DestinationGuard(AllowList.of(List.of()), resolver()), bodyPort,
                sender);

        assertThatThrownBy(() -> target.call(send()))
                .isInstanceOfSatisfying(TargetFaultException.class, fault ->
                        assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        assertThat(sent).as("nothing sent to a refused destination").isEmpty();
    }

    @Test
    @DisplayName("an allow-listed name that does not resolve is TRANSIENT (connect family)")
    void unresolvedHostIsTransient() {
        HostResolver broken = host -> {
            throw new UnknownHostException(host);
        };
        ApiTargetCall target = new ApiTargetCall(views(view("http://target.e5.test:8080/api", "POST", 1500,
                "NO_AUTH", false, Map.of())),
                new DestinationGuard(AllowList.of(List.of("target.e5.test")), broken), bodyPort,
                neverExpected());

        assertThatThrownBy(() -> target.call(send()))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);
    }

    @Test
    @DisplayName("a connect failure is TRANSIENT - nothing was sent, the try may repeat")
    void connectFailureIsTransient() {
        RecordingHttpSend sender = failing(() -> new IOException("connection refused"));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of()), sender);

        assertThatThrownBy(() -> target.call(send()))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);
    }

    @Test
    @DisplayName("a read timeout is AMBIGUOUS without idempotent receive")
    void timeoutIsAmbiguous() {
        RecordingHttpSend sender = failing(() -> new SocketTimeoutException("read timed out"));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of()), sender);

        assertThatThrownBy(() -> target.call(send())).isInstanceOfSatisfying(TargetFaultException.class,
                fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.AMBIGUOUS));
    }

    @Test
    @DisplayName("a read timeout is TRANSIENT when the target declares idempotent receive")
    void timeoutIsTransientForIdempotentTarget() {
        RecordingHttpSend sender = failing(() -> new SocketTimeoutException("read timed out"));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", true,
                Map.of()), sender);

        assertThatThrownBy(() -> target.call(send()))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);
    }

    @Test
    @DisplayName("503 with no dictionary is TRANSIENT")
    void serviceUnavailableIsTransient() {
        RecordingHttpSend sender = answering(() -> new Response(503, new byte[0]));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of()), sender);

        assertThatThrownBy(() -> target.call(send()))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);
    }

    @Test
    @DisplayName("500 on an idempotent target is TRANSIENT, on a plain one AMBIGUOUS")
    void serverErrorFollowsTheIdempotentDeclaration() {
        RecordingHttpSend idempotent = answering(() -> new Response(500, new byte[0]));
        ApiTargetCall toIdempotent = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", true,
                Map.of()), idempotent);
        assertThatThrownBy(() -> toIdempotent.call(send()))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);

        RecordingHttpSend plain = answering(() -> new Response(500, new byte[0]));
        ApiTargetCall toPlain = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of()), plain);
        assertThatThrownBy(() -> toPlain.call(send())).isInstanceOfSatisfying(TargetFaultException.class,
                fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.AMBIGUOUS));
    }

    @Test
    @DisplayName("403 is PERMANENT whatever the target's dictionary says")
    void forbiddenIsAlwaysPermanent() {
        RecordingHttpSend sender = answering(() -> new Response(403, new byte[0]));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of(403, OutcomeClass.TRANSIENT)), sender);

        assertThatThrownBy(() -> target.call(send())).isInstanceOfSatisfying(TargetFaultException.class,
                fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
    }

    @Test
    @DisplayName("the target's dictionary re-classs 503 as PERMANENT - the override wins")
    void dictionaryOverrideWins() {
        RecordingHttpSend sender = answering(() -> new Response(503, new byte[0]));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of(503, OutcomeClass.PERMANENT)), sender);

        assertThatThrownBy(() -> target.call(send())).isInstanceOfSatisfying(TargetFaultException.class,
                fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
    }

    @Test
    @DisplayName("no endpoint view found is PERMANENT and nothing is sent")
    void missingEndpointConfigurationIsPermanent() {
        RecordingHttpSend sender = answering(() -> new Response(200, new byte[0]));
        ApiTargetCall target = new ApiTargetCall(id -> Optional.empty(), guard(), bodyPort, sender);

        assertThatThrownBy(() -> target.call(send())).isInstanceOfSatisfying(TargetFaultException.class,
                fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("no transform result for the send is PERMANENT and nothing is sent")
    void missingBodyIsPermanent() {
        ApiTargetBody noResult = send -> Optional.empty();
        RecordingHttpSend sender = answering(() -> new Response(200, new byte[0]));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of()), sender, noResult);

        assertThatThrownBy(() -> target.call(send())).isInstanceOfSatisfying(TargetFaultException.class,
                fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        assertThat(sent).as("nothing is sent without a body").isEmpty();
    }

    @Test
    @DisplayName("a credential-based auth method stays TRANSIENT: the send waits for the E10 credentials, never"
            + " dead-letters on a missing slice")
    void credentialAuthWaitsTransient() {
        RecordingHttpSend sender = answering(() -> new Response(200, new byte[0]));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "API_KEY", false,
                Map.of()), sender);

        assertThatThrownBy(() -> target.call(send()))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);
        assertThat(sent).as("no credential is held, so no call is attempted").isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("T-124: a 3xx redirect answer is PERMANENT and never followed - the guard checked one destination,"
            + " the redirect target (even a refused one) is never dialled")
    void redirectIsPermanentAndNeverRefollowed() {
        RecordingHttpSend sender = answering(() -> new Response(302,
                "Location: http://169.254.169.254/latest/meta-data".getBytes(StandardCharsets.UTF_8)));
        ApiTargetCall target = target(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of()), sender);

        assertThatThrownBy(() -> target.call(send()))
                .isInstanceOfSatisfying(TargetFaultException.class, fault ->
                        assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        assertThat(sent).as("one send, never a second dial of the redirect target").hasSize(1);
    }

    @Test
    @Tag("negative")
    @DisplayName("T-124: a name on the allow-list that resolves to 127.0.0.1 is refused AFTER the DNS lookup -"
            + " PERMANENT, metered, nothing dialled")
    void allowListedNameResolvingToLoopbackIpv4IsRefusedAfterDns() {
        refusesAfterDns("127.0.0.1");
    }

    @Test
    @Tag("negative")
    @DisplayName("T-124: a name on the allow-list that resolves to ::1 is refused after the DNS lookup")
    void allowListedNameResolvingToLoopbackIpv6IsRefusedAfterDns() {
        refusesAfterDns("::1");
    }

    @Test
    @Tag("negative")
    @DisplayName("T-124: a name on the allow-list that resolves to a private range is refused after DNS")
    void allowListedNameResolvingToPrivateRangeIsRefusedAfterDns() {
        refusesAfterDns("10.11.12.13");
    }

    @Test
    @Tag("negative")
    @DisplayName("T-124: a name on the allow-list that resolves to the cloud metadata address is refused after DNS")
    void allowListedNameResolvingToCloudMetadataIsRefusedAfterDns() {
        refusesAfterDns("169.254.169.254");
    }

    @Test
    @Tag("negative")
    @DisplayName("T-124: a host not on the allow-list is refused, metered and never dialled")
    void hostNotOnTheAllowListIsRefusedMetered() {
        RecordingHttpSend sender = answering(() -> new Response(200, new byte[0]));
        TargetEndpointView view = view("http://not-listed.e5.test:8080/api", "POST", 1500, "NO_AUTH", false, Map.of());

        assertThatThrownBy(() -> metered(view, sender).call(send()))
                .isInstanceOfSatisfying(TargetFaultException.class, fault ->
                        assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        assertThat(sent).as("nothing sent to a non-allow-listed host").isEmpty();
        assertThat(meters.get("api.target.outcome").tags("target_type", "API", "outcome", "PERMANENT").counter()
                .count()).as("the refusal is metered").isEqualTo(1);
    }

    /** One resolve-then-refuse leg: the guard sees the address only after the (stubbed) DNS lookup. */
    private void refusesAfterDns(String resolvesTo) {
        RecordingHttpSend sender = answering(() -> new Response(200, new byte[0]));
        TargetEndpointView view = view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false, Map.of());
        SimpleMeterRegistry refusedMeters = new SimpleMeterRegistry();

        assertThatThrownBy(() -> new ApiTargetCall(views(view), resolveTo(resolvesTo), bodyPort, sender,
                CircuitBreakerRegistry.ofDefaults(), refusedMeters, ObservationRegistry.NOOP).call(send()))
                .isInstanceOfSatisfying(TargetFaultException.class, fault ->
                        assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        assertThat(sent).as("nothing is dialled at a " + resolvesTo + " destination").isEmpty();
        assertThat(refusedMeters.get("api.target.outcome").tags("target_type", "API", "outcome", "PERMANENT").counter()
                .count()).as("the refusal is metered").isEqualTo(1);
        assertThat(refusedMeters.get("api.target.send").tags("target_type", "API").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum()).as("the refused attempt is timed")
                .isEqualTo(1);
    }

    /** A guard whose allow-listed target always resolves to one fixed address (a stubbed DNS). */
    private static DestinationGuard resolveTo(String address) {
        return new DestinationGuard(AllowList.of(List.of("target.e5.test")), host -> List.of(addressLiteral(address)));
    }

    private ApiTargetCall metered(TargetEndpointView view, HttpSend sender) {
        return new ApiTargetCall(views(view), guard(), bodyPort, sender, CircuitBreakerRegistry.ofDefaults(), meters,
                ObservationRegistry.NOOP);
    }

    private static InetAddress addressLiteral(String literal) {
        return address(literal); // a literal: parsed, never a DNS query
    }

    @Test
    @DisplayName("T96: a half-open breaker re-drives at most its trial-call count per target; the rest raise"
            + " TRANSIENT and never reach the socket, and another target's breaker is untouched")
    void halfOpenBreakerReDrivesAtMostItsTrialCallsPerTarget() {
        int trialCalls = 2;
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .permittedNumberOfCallsInHalfOpenState(trialCalls).build());
        ApiTargetCall target = new ApiTargetCall(views(view("http://target.e5.test:8080/api", "POST", 1500,
                "NO_AUTH", false, Map.of())), guard(), bodyPort,
                answering(() -> new Response(503, new byte[0])), breakers);
        UUID endpoint = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> target.call(sendTo(endpoint)))
                    .isInstanceOf(TargetFaultException.TransientFaultException.class);
        }
        assertThat(sent).as("the breaker opens after its window of 2 failures").hasSize(2);

        breakers.circuitBreaker(endpoint.toString()).transitionToHalfOpenState();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> target.call(sendTo(endpoint)))
                    .isInstanceOf(TargetFaultException.TransientFaultException.class);
        }
        assertThat(sent).as("half-open lets exactly its trial calls through").hasSize(2 + trialCalls);

        assertThatThrownBy(() -> target.call(sendTo(UUID.randomUUID())))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);
        assertThat(sent).as("one breaker per target: another target still sends").hasSize(3 + trialCalls);
    }

    // ---------------------------------------------------------------- T99: every call is observable (T-167)

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<String> observed = new ArrayList<>();
    private final ObservationRegistry observations = recording(observed);

    @Test
    @DisplayName("T99: success, transient, permanent and breaker-open each count their outcome and time the attempt,"
            + " tagged target_type=API")
    void everyOutcomeIsCountedAndTimed() {
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        TargetEndpointView view = view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false, Map.of());
        observed(view, answering(() -> new Response(200, new byte[0])), breakers).call(send());
        assertThatThrownBy(() -> observed(view, answering(() -> new Response(503, new byte[0])), breakers)
                .call(send())).isInstanceOf(TargetFaultException.TransientFaultException.class);
        assertThatThrownBy(() -> observed(view, answering(() -> new Response(401, new byte[0])), breakers)
                .call(send())).isInstanceOfSatisfying(TargetFaultException.class, fault ->
                        assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        UUID open = UUID.randomUUID();
        breakers.circuitBreaker(open.toString()).transitionToOpenState();
        assertThatThrownBy(() -> observed(view, neverExpected(), breakers).call(sendTo(open)))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);

        assertThat(meters.get("api.target.outcome").tags("target_type", "API", "outcome", "SUCCESS").counter()
                .count()).isEqualTo(1);
        assertThat(meters.get("api.target.outcome").tags("target_type", "API", "outcome", "PERMANENT").counter()
                .count()).isEqualTo(1);
        assertThat(meters.get("api.target.outcome").tags("target_type", "API", "outcome", "TRANSIENT").counter()
                .count()).as("the 503 and the breaker-open refusal").isEqualTo(2);
        assertThat(meters.get("api.target.send").tags("target_type", "API").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum()).isEqualTo(4);
        assertThat(meters.get("resilience4j.circuitbreaker.state").gauges()).as("breaker state is a gauge")
                .isNotEmpty();
    }

    @Test
    @DisplayName("T99: one api.target.send observation is started and stopped per attempt")
    void oneObservationPerAttempt() {
        TargetEndpointView view = view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false, Map.of());
        ApiTargetCall target = observed(view, answering(() -> new Response(200, new byte[0])),
                CircuitBreakerRegistry.ofDefaults());

        target.call(send());
        target.call(send());

        assertThat(observed).containsExactly("start api.target.send", "stop api.target.send outcome=SUCCESS",
                "start api.target.send", "stop api.target.send outcome=SUCCESS");
    }

    @Test
    @DisplayName("T99/T-123: endpoint A's breaker OPEN - endpoint B's call still goes out and B's breaker stays"
            + " CLOSED")
    void oneOpenBreakerNeverStopsAnotherTarget() {
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        ApiTargetCall target = observed(view("http://target.e5.test:8080/api", "POST", 1500, "NO_AUTH", false,
                Map.of()), answering(() -> new Response(200, new byte[0])), breakers);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        breakers.circuitBreaker(a.toString()).transitionToOpenState();

        assertThatThrownBy(() -> target.call(sendTo(a)))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);
        target.call(sendTo(b));

        assertThat(sent).as("only B's call reached the wire").hasSize(1);
        assertThat(breakers.circuitBreaker(b.toString()).getState())
                .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        assertThat(meters.get("api.target.outcome").tags("outcome", "SUCCESS").counter().count()).isEqualTo(1);
    }

    private ApiTargetCall observed(TargetEndpointView view, HttpSend sender, CircuitBreakerRegistry breakers) {
        return new ApiTargetCall(views(view), guard(), bodyPort, sender, breakers, meters, observations);
    }

    /** A recording handler: micrometer-observation-test is not on this module's test classpath. */
    private static ObservationRegistry recording(List<String> log) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStart(Observation.Context context) {
                log.add("start " + context.getName());
            }

            @Override
            public void onStop(Observation.Context context) {
                log.add("stop " + context.getName() + " outcome="
                        + context.getLowCardinalityKeyValue("outcome").getValue());
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        return registry;
    }

    // ---------------------------------------------------------------- the seams

    /** The adapter under proof over a recording seam: the real socket send is the container test's business. */
    private ApiTargetCall target(TargetEndpointView view, HttpSend sender) {
        return target(view, sender, bodyPort);
    }

    private ApiTargetCall target(TargetEndpointView view, HttpSend sender, ApiTargetBody bodyPort) {
        return new ApiTargetCall(views(view), guard(), bodyPort, sender);
    }

    private DestinationGuard guard() {
        return new DestinationGuard(AllowList.of(List.of("target.e5.test")), resolver());
    }

    private HostResolver resolver() {
        return host -> List.of(PINNED);
    }

    private TargetEndpointViews views(TargetEndpointView view) {
        return id -> Optional.of(view);
    }

    private static TargetEndpointView view(String uri, String method, int timeoutMs, String authMethod,
            boolean idempotentReceive, Map<Integer, OutcomeClass> dictionary) {
        return new TargetEndpointView(URI.create(uri), method, timeoutMs, authMethod, idempotentReceive, dictionary);
    }

    private static Stage2Send send() {
        return sendTo(UUID.randomUUID());
    }

    private static Stage2Send sendTo(UUID endpoint) {
        return new Stage2Send(DELIVERY, UUID.randomUUID(), "CUST-1", endpoint, false);
    }

    private interface Answer {
        Response give() throws IOException;
    }

    private RecordingHttpSend answering(Answer answer) {
        try {
            return new RecordingHttpSend(sent, answer.give());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private RecordingHttpSend failing(java.util.function.Supplier<? extends IOException> failure) {
        return new RecordingHttpSend(sent, failure);
    }

    private RecordingHttpSend neverExpected() {
        return new RecordingHttpSend(sent);
    }

    /** The seam stub: records the call, answers or throws on demand; a call reaching it un-stubbed fails the leg. */
    private static final class RecordingHttpSend implements HttpSend {

        private final List<Call> sink;
        private final Answer onDemand;
        private final Response canned;
        private final java.util.function.Supplier<? extends IOException> fault;

        RecordingHttpSend(List<Call> sink) {
            this(sink, null, null, null);
        }

        RecordingHttpSend(List<Call> sink, Response canned) {
            this(sink, null, canned, null);
        }

        RecordingHttpSend(List<Call> sink, java.util.function.Supplier<? extends IOException> fault) {
            this(sink, null, null, fault);
        }

        private RecordingHttpSend(List<Call> sink, Answer onDemand, Response canned,
                java.util.function.Supplier<? extends IOException> fault) {
            this.sink = sink;
            this.onDemand = onDemand;
            this.canned = canned;
            this.fault = fault;
        }

        @Override
        public Response send(CheckedDestination destination, String method, Map<String, String> headers,
                byte[] body, int timeoutMs) throws IOException {
            if (onDemand == null && canned == null && fault == null) {
                throw new AssertionError("the adapter must never reach the socket without a destination refused"
                        + " or configured - the leg stubs neither");
            }
            sink.add(new Call(destination, method, headers, body, timeoutMs));
            if (fault != null) {
                throw fault.get();
            }
            if (onDemand != null) {
                return onDemand.give();
            }
            return canned;
        }
    }

    private record Call(CheckedDestination destination, String method, Map<String, String> headers, byte[] body,
            int timeoutMs) {
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal); // a literal, never a DNS query
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}