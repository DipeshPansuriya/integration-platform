package io.integrationplatform.routing.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.adapter.api.HttpSend.Response;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
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
        return new Stage2Send(DELIVERY, UUID.randomUUID(), "CUST-1", UUID.randomUUID(), false);
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