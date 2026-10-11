package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.destination.OutboundHttpCheck;
import io.integrationplatform.config.destination.OutboundHttpCheck.Outcome;
import io.integrationplatform.config.destination.OutboundHttpCheck.Result;
import io.integrationplatform.config.destination.SocketDialer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-104: the guarded HTTP check end to end on an in-process server - no Docker, no real DNS (R03). The resolver and
 * the dialer are the injected ports: the resolver decides what a name resolves to, the dialer is where "the network
 * behind the pinned address" is reached. What is proven here is what only a fake network can show: the pinned address
 * is the one dialled (no second lookup, so DNS rebinding cannot swap it), a refused destination is never dialled (no
 * byte is sent), and every redirect hop goes through the guard again.
 */
class OutboundHttpCheckTest {

    private static final InetAddress PUBLIC = literal("203.0.113.10");
    private static final InetAddress PUBLIC_2 = literal("198.51.100.20");

    private CannedHttpServer server;
    private final List<InetSocketAddress> dialled = new CopyOnWriteArrayList<>();

    private static InetAddress literal(String text) {
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = new CannedHttpServer(CannedHttpServer.answer(204));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    /** Records the address it is asked for, then connects to the in-process server instead. */
    private SocketDialer dialer() {
        return (address, connectTimeoutMillis) -> {
            dialled.add(address);
            Socket socket = new Socket();
            socket.connect(server.address(), connectTimeoutMillis);
            return socket;
        };
    }

    private OutboundHttpCheck check(HostResolver resolver) {
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("*.e5.test")), resolver);
        return new OutboundHttpCheck(guard, dialer(), OutboundHttpCheck.Settings.defaults(), new SimpleMeterRegistry(),
                ObservationRegistry.NOOP);
    }

    private static HostResolver fixed(Map<String, InetAddress> names) {
        return host -> {
            InetAddress found = names.get(host);
            if (found == null) {
                throw new UnknownHostException(host);
            }
            return List.of(found);
        };
    }

    @Test
    @Tag("security")
    @DisplayName("DNS rebinding: the host is resolved once and the checked address is the one dialled")
    void theCheckedAddressIsTheDialledOneWithNoSecondLookup() {
        AtomicInteger lookups = new AtomicInteger();
        // first answer public, every later answer loopback: a second lookup would reach the inside
        HostResolver rebinding = host -> List.of(lookups.getAndIncrement() == 0 ? PUBLIC : literal("127.0.0.1"));

        Result result = check(rebinding).check(URI.create("http://api.e5.test/ping"), false, null);

        assertThat(result.outcome()).as(result.toString()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.httpStatus()).isEqualTo(204);
        assertThat(result.responseTimeMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(lookups.get()).as("exactly one resolution").isEqualTo(1);
        assertThat(dialled).containsExactly(new InetSocketAddress(PUBLIC, 80));
        assertThat(server.requestLines()).containsExactly("GET /ping HTTP/1.1");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A destination resolving to a private range is refused before any byte is sent")
    void aPrivateResolutionIsNeverDialled() {
        Result result = check(fixed(Map.of("inside.e5.test", literal("10.0.0.7"))))
                .check(URI.create("http://inside.e5.test/"), true, null);

        assertThat(result.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(result.reason()).isEqualTo("REFUSED_ADDRESS");
        assertThat(result.detail()).isEqualTo("private");
        assertThat(dialled).isEmpty();
        assertThat(server.connections()).isZero();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A host off the allow-list is refused and never dialled")
    void aHostOffTheAllowListIsNeverDialled() {
        Result result = check(fixed(Map.of("evil.example", PUBLIC))).check(URI.create("https://evil.example/"), true,
                null);

        assertThat(result.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(result.reason()).isEqualTo("NOT_ON_ALLOW_LIST");
        assertThat(dialled).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A redirect to a private address is re-checked and refused: only the first hop is ever dialled")
    void aRedirectHopIsReCheckedAndRefused() {
        server.answerWith(CannedHttpServer.answer(302, "Location: http://inner.e5.test/admin"));
        Result result = check(fixed(Map.of("api.e5.test", PUBLIC, "inner.e5.test", literal("192.168.1.10"))))
                .check(URI.create("http://api.e5.test/start"), true, null);

        assertThat(result.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(result.reason()).isEqualTo("REFUSED_ADDRESS");
        assertThat(dialled).containsExactly(new InetSocketAddress(PUBLIC, 80));
        assertThat(server.connections()).isEqualTo(1);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A redirect to a literal loopback address is refused")
    void aRedirectToLoopbackIsRefused() {
        server.answerWith(CannedHttpServer.answer(307, "Location: http://127.0.0.1:8080/"));
        Result result = check(fixed(Map.of("api.e5.test", PUBLIC, "127.0.0.1", literal("127.0.0.1"))))
                .check(URI.create("http://api.e5.test/"), true, null);

        assertThat(result.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(server.connections()).isEqualTo(1);
    }

    @Test
    @DisplayName("An allowed redirect is followed through the guard to its pinned address")
    void anAllowedRedirectIsFollowed() throws Exception {
        try (CannedHttpServer second = new CannedHttpServer(CannedHttpServer.answer(200))) {
            server.answerWith(CannedHttpServer.answer(301, "Location: http://next.e5.test:8081/final"));
            SocketDialer twoHosts = (address, timeout) -> {
                dialled.add(address);
                Socket socket = new Socket();
                socket.connect(address.getAddress().equals(PUBLIC) ? server.address() : second.address(), timeout);
                return socket;
            };
            DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("*.e5.test")),
                    fixed(Map.of("api.e5.test", PUBLIC, "next.e5.test", PUBLIC_2)));
            OutboundHttpCheck check = new OutboundHttpCheck(guard, twoHosts, OutboundHttpCheck.Settings.defaults(),
                    new SimpleMeterRegistry(), ObservationRegistry.NOOP);

            Result result = check.check(URI.create("http://api.e5.test/"), true, null);

            assertThat(result.outcome()).as(result.toString()).isEqualTo(Outcome.SUCCESS);
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(dialled).containsExactly(new InetSocketAddress(PUBLIC, 80), new InetSocketAddress(PUBLIC_2, 8081));
        }
    }

    @Test
    @DisplayName("Redirects are off unless the system enables them: the 3xx answer is the result (ADR-010 section 8)")
    void redirectsAreOffByDefault() {
        server.answerWith(CannedHttpServer.answer(302, "Location: http://inner.e5.test/"));
        Result result = check(fixed(Map.of("api.e5.test", PUBLIC))).check(URI.create("http://api.e5.test/"), false,
                null);

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.httpStatus()).isEqualTo(302);
        assertThat(server.connections()).isEqualTo(1);
    }

    @Test
    @Tag("negative")
    @DisplayName("A redirect loop stops at the hop limit with a clean failure")
    void aRedirectLoopStops() {
        server.answerWith(CannedHttpServer.answer(302, "Location: /again"));
        Result result = check(fixed(Map.of("api.e5.test", PUBLIC))).check(URI.create("http://api.e5.test/"), true,
                null);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.reason()).isEqualTo("TOO_MANY_REDIRECTS");
        assertThat(server.connections()).isEqualTo(OutboundHttpCheck.Settings.defaults().maxRedirects() + 1);
    }

    @Test
    @Tag("negative")
    @DisplayName("A name that does not resolve is a clean failure, not a refusal and not an exception")
    void anUnresolvedNameFailsCleanly() {
        Result result = check(fixed(Map.of())).check(URI.create("http://nx.e5.test/"), false, Duration.ofSeconds(1));

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.reason()).isEqualTo("UNRESOLVED");
        assertThat(dialled).isEmpty();
    }
}
