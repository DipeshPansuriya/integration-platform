package io.integrationplatform.routing.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.adapter.api.HttpSend.Response;
import io.integrationplatform.routing.credential.TargetCallCredentialHolder;
import io.integrationplatform.routing.credential.TestOpenedCredentials;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Row 510 (security, finding 2 of the PR 40 re-verify): the OAuth2 token URL is a tenant-chosen address that receives
 * the tenant admin's sealed client secret, so it goes through the SAME {@link DestinationGuard} as the base URL, at
 * call time and BEFORE the secret is opened: literal, allow-list, scheme, private range, platform host, DNS pinned.
 * A refusal is PERMANENT, the fault text names neither host nor port nor reason (no reachability oracle), and no
 * byte is dialled. The API_KEY header name and secret values are checked for line breaks and reserved names.
 */
class ApiTargetCallOAuth2TokenUrlTest {

    private static final InetAddress PUBLIC = address("203.0.113.10");
    private static final UUID DELIVERY = UUID.fromString("dddd0000-0000-4000-8000-000000000510");
    private static final String SECRET = "TOK-URL-TEST-SECRET";

    private final List<Dial> dials = new ArrayList<>();
    private final ApiTargetBody bodyPort = send -> Optional.of(
            new ApiTargetBody.ApiWireBody("application/json", "{}".getBytes(StandardCharsets.UTF_8)));

    @AfterEach
    void clearCredentials() {
        TargetCallCredentialHolder.clear();
    }

    /** The seam: answers the token host with a token, the target with 200; records every dial. */
    private HttpSend sender() {
        return (CheckedDestination destination, String method, Map<String, String> headers, byte[] body,
                int timeoutMs) -> {
            dials.add(new Dial(destination, method, headers, new String(body, StandardCharsets.UTF_8)));
            return "application/x-www-form-urlencoded".equals(headers.get("Content-Type"))
                    ? new Response(200, "{\"access_token\":\"tok-1\"}".getBytes(StandardCharsets.UTF_8))
                    : new Response(200, new byte[0]);
        };
    }

    private DestinationGuard guard() {
        // kafka is both allow-listed and a platform host: the platform-host refusal must win
        return new DestinationGuard(AllowList.of(List.of("target.e5.test", "auth.partner.example", "kafka",
                "127.0.0.1", "169.254.169.254", "[::1]")), host -> List.of(PUBLIC), List.of(), List.of(),
                List.of("kafka"));
    }

    private ApiTargetCall target(String tokenUrl, DestinationGuard guard) {
        TargetEndpointView view = new TargetEndpointView(URI.create("https://target.e5.test/api"), "POST", 1500,
                "OAUTH2_CLIENT_CREDENTIALS", "cid", tokenUrl, false, Map.of());
        HttpSend sender = sender();
        return new ApiTargetCall(id -> Optional.of(view), guard, bodyPort, sender, CircuitBreakerRegistry.ofDefaults(),
                new SimpleMeterRegistry(), ObservationRegistry.NOOP, new OAuth2TokenFetcher(sender));
    }

    @ParameterizedTest(name = "[{index}] token URL {0} is refused PERMANENT, nothing dialled, secret never opened")
    @ValueSource(strings = {
            "http://127.0.0.1:9/t",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/t",
            "http://kafka:9092/",
            "https://kafka:9092/",
            "http://u:p@auth.partner.example/t",
            "https://u:p@auth.partner.example/t",
            "https://not-listed.example/t",
            "http://auth.partner.example/t",
            "file:///etc/passwd",
            "ftp://auth.partner.example/t",
            "not a url ::",
            "https:///t"})
    @Tag("negative")
    @Tag("security")
    void aRefusedTokenUrlIsPermanentAndNothingIsDialled(String tokenUrl) {
        // NO sealed OAUTH_CLIENT_SECRET in the holder: the guard must speak BEFORE the secret is requested
        TargetCallCredentialHolder.set(TestOpenedCredentials.of(Map.of()));

        assertThatThrownBy(() -> target(tokenUrl, guard()).call(send()))
                .isInstanceOfSatisfying(TargetFaultException.class, fault -> {
                    assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT);
                    assertThat(fault).isNotInstanceOf(TargetFaultException.TransientFaultException.class);
                    assertThat(fault.getMessage()).as("no host, port, address or reason in the tenant-readable text")
                            .doesNotContain("127.0.0.1").doesNotContain("169.254").doesNotContain("kafka")
                            .doesNotContain("9092").doesNotContain("::1").doesNotContain("partner")
                            .doesNotContain("not-listed").doesNotContain("passwd").doesNotContain("loopback")
                            .doesNotContain("link-local").doesNotContain("platform-host")
                            .doesNotContain("OAUTH_CLIENT_SECRET");
                });
        assertThat(dials).as("not one byte is dialled for a refused token URL").isEmpty();
    }

    @Test
    @Tag("security")
    @DisplayName("an allow-listed https host that RESOLVES to a private address is refused after DNS (no rebinding gap)")
    void aTokenHostResolvingToAPrivateAddressIsRefused() {
        TargetCallCredentialHolder.set(TestOpenedCredentials.of(Map.of("OAUTH_CLIENT_SECRET", SECRET)));
        DestinationGuard rebound = new DestinationGuard(AllowList.of(List.of("target.e5.test", "auth.partner.example")),
                host -> host.startsWith("auth.") ? List.of(address("10.0.0.7")) : List.of(PUBLIC));

        assertThatThrownBy(() -> target("https://auth.partner.example/t", rebound).call(send()))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        assertThat(dials).isEmpty();
    }

    @Test
    @DisplayName("an allow-listed https token host is accepted: the token POST dials the PINNED address, then the"
            + " call goes out with the bearer")
    void aListedHttpsTokenHostIsAcceptedAndPinned() {
        TargetCallCredentialHolder.set(TestOpenedCredentials.of(Map.of("OAUTH_CLIENT_SECRET", SECRET)));

        target("https://auth.partner.example/oauth/token", guard()).call(send());

        assertThat(dials).hasSize(2);
        Dial tokenCall = dials.get(0);
        assertThat(tokenCall.destination().pinnedAddress()).isEqualTo(PUBLIC);
        assertThat(tokenCall.destination().host()).isEqualTo("auth.partner.example");
        assertThat(tokenCall.destination().tls()).isTrue();
        assertThat(tokenCall.method()).isEqualTo("POST");
        assertThat(tokenCall.headers()).containsEntry("Content-Type", "application/x-www-form-urlencoded");
        assertThat(tokenCall.body()).contains("grant_type=client_credentials").contains("client_id=cid")
                .contains("client_secret=" + SECRET);
        assertThat(dials.get(1).headers()).containsEntry("Authorization", "Bearer tok-1");
    }

    @Test
    @DisplayName("an allow-listed token host that does not resolve is TRANSIENT (the address's fault, not the policy's)")
    void anUnresolvableTokenHostIsTransient() {
        TargetCallCredentialHolder.set(TestOpenedCredentials.of(Map.of("OAUTH_CLIENT_SECRET", SECRET)));
        HostResolver broken = host -> {
            if (host.startsWith("auth.")) {
                throw new UnknownHostException(host);
            }
            return List.of(PUBLIC);
        };

        assertThatThrownBy(() -> target("https://auth.partner.example/t",
                new DestinationGuard(AllowList.of(List.of("target.e5.test", "auth.partner.example")), broken))
                .call(send())).isInstanceOf(TargetFaultException.TransientFaultException.class);
        assertThat(dials).isEmpty();
    }

    @Test
    @DisplayName("the showcase exception lifts plain http for the exact local host and port only (as for the base URL)")
    void showcaseLocalHostMayUsePlainHttpOnItsExactPortOnly() {
        TargetCallCredentialHolder.set(TestOpenedCredentials.of(Map.of("OAUTH_CLIENT_SECRET", SECRET)));
        DestinationGuard showcase = new DestinationGuard(AllowList.of(List.of("target.e5.test", "wiremock")),
                host -> host.equals("wiremock") ? List.of(address("172.20.0.5")) : List.of(PUBLIC),
                List.of("wiremock"), List.of(8080));

        target("http://wiremock:8080/token", showcase).call(send());
        assertThat(dials.get(0).destination().host()).isEqualTo("wiremock");

        dials.clear();
        assertThatThrownBy(() -> target("http://wiremock:9999/token", showcase).call(send()))
                .isInstanceOf(TargetFaultException.class);
        assertThat(dials).isEmpty();
    }

    @Test
    @Tag("security")
    @DisplayName("real loopback sink: a token URL aimed at an in-process server is never contacted")
    void aLoopbackSinkReceivesNothing() throws Exception {
        AtomicInteger journal = new AtomicInteger();
        HttpServer sink = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        sink.createContext("/", exchange -> {
            journal.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        sink.start();
        try {
            TargetCallCredentialHolder.set(TestOpenedCredentials.of(Map.of("OAUTH_CLIENT_SECRET", SECRET)));
            String url = "http://127.0.0.1:" + sink.getAddress().getPort() + "/t";

            assertThatThrownBy(() -> target(url, guard()).call(send())).isInstanceOf(TargetFaultException.class);

            assertThat(journal.get()).as("the sink journal").isZero();
        } finally {
            sink.stop(0);
        }
    }

    @ParameterizedTest(name = "[{index}] API_KEY header name \"{0}\" is refused PERMANENT, never put on the wire")
    @ValueSource(strings = {"X-Key\r\nX-Evil: 1", "X-Key\nX", "Idempotency-Key", "content-type", "HOST", "Authorization",
            "Content-Length", "Transfer-Encoding", "traceparent", "X Key", "X-Key:", " "})
    @Tag("security")
    void aReservedOrMalformedApiKeyHeaderNameIsRefused(String headerName) {
        TargetCallCredentialHolder.set(TestOpenedCredentials.of(Map.of("API_KEY", SECRET)));
        TargetEndpointView view = new TargetEndpointView(URI.create("https://target.e5.test/api"), "POST", 1500,
                "API_KEY", headerName, null, false, Map.of());
        ApiTargetCall call = new ApiTargetCall(id -> Optional.of(view), guard(), bodyPort, sender());

        assertThatThrownBy(() -> call.call(send())).isInstanceOfSatisfying(TargetFaultException.class,
                fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        assertThat(dials).isEmpty();
    }

    @Test
    @DisplayName("a normal API_KEY header name still works")
    void aPlainApiKeyHeaderNameIsSent() {
        TargetCallCredentialHolder.set(TestOpenedCredentials.of(Map.of("API_KEY", SECRET)));
        TargetEndpointView view = new TargetEndpointView(URI.create("https://target.e5.test/api"), "POST", 1500,
                "API_KEY", "X-Api-Key", null, false, Map.of());

        new ApiTargetCall(id -> Optional.of(view), guard(), bodyPort, sender()).call(send());

        assertThat(dials).hasSize(1);
        assertThat(dials.get(0).headers()).containsEntry("X-Api-Key", SECRET);
    }

    @Test
    @Tag("security")
    @DisplayName("a secret VALUE with a line break never reaches the wire (header injection through the key or a token)")
    void aSecretValueWithALineBreakIsRefused() {
        TargetCallCredentialHolder.set(TestOpenedCredentials.of(Map.of("API_KEY", "k\r\nX-Evil: 1")));
        TargetEndpointView view = new TargetEndpointView(URI.create("https://target.e5.test/api"), "POST", 1500,
                "API_KEY", "X-Api-Key", null, false, Map.of());

        assertThatThrownBy(() -> new ApiTargetCall(id -> Optional.of(view), guard(), bodyPort, sender()).call(send()))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        assertThat(dials).isEmpty();
    }

    private static Stage2Send send() {
        return new Stage2Send(DELIVERY, UUID.randomUUID(), "CUST-1", UUID.randomUUID(), false);
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal); // a literal, never a DNS query
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Dial(CheckedDestination destination, String method, Map<String, String> headers, String body) {
    }
}
