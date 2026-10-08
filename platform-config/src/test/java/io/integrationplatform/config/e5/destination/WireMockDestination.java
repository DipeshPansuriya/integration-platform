package io.integrationplatform.config.e5.destination;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.destination.SocketDialer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The allowed destination of the E5-F1-S2 suites: WireMock in a Testcontainers {@link GenericContainer}, pinned by tag
 * AND digest (R05; declared here, not in platform-app's TestImages - this module cannot import it), stubbed through its
 * admin REST API with the JDK HttpClient (no WireMock Java dependency is added).
 *
 * <p>The guard refuses loopback for ever, and the container's published port is on the loopback interface of this PC,
 * so the suites reach WireMock through the two injected ports: {@link #resolver()} makes the public test name
 * {@value #HOST} resolve to the documentation address {@code 203.0.113.10} (TEST-NET-3, never routed), which the guard
 * accepts and pins, and {@link #dialer()} connects that pinned address - and only that one - to the published port.
 * Every other address the dialer is asked for is refused with a connect failure, so no test can ever reach a real host.
 */
public final class WireMockDestination {

    /** wiremock/wiremock 3.13.1, the multi-arch index digest (R05: tag and digest). */
    public static final DockerImageName IMAGE = DockerImageName.parse(
            "wiremock/wiremock:3.13.1@sha256:d61e7720f89483fdef5366843b58d1dfd06bcce5828179c9f2f54de5c28354b0");

    /** The public test name of the allowed destination (on the suites' allow-list as {@code *.e5.test}). */
    public static final String HOST = "wiremock.e5.test";

    /** The documentation address the test name resolves to; the dialer maps it to the container. */
    public static final InetAddress PINNED = literal("203.0.113.10");

    /** The port the destination is addressed on: WireMock's own port inside the container. */
    public static final int PORT = 8080;

    private static final HttpClient ADMIN = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Pattern COUNT = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");

    private final GenericContainer<?> container;
    private final Map<String, List<InetAddress>> names = new ConcurrentHashMap<>();
    private final List<InetSocketAddress> dialled = new CopyOnWriteArrayList<>();

    public WireMockDestination() {
        container = new GenericContainer<>(IMAGE)
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    public static InetAddress literal(String text) {
        try {
            return InetAddress.getByName(text); // a literal, never a DNS query
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    public WireMockDestination start() {
        if (!container.isRunning()) {
            container.start();
        }
        names.put(HOST, List.of(PINNED));
        return this;
    }

    public void stop() {
        container.stop();
    }

    /** The address a system registers: {@code http://wiremock.e5.test:8080<path>}. */
    public static String address(String path) {
        return "http://" + HOST + ":" + PORT + path;
    }

    /** Makes {@code host} resolve to {@code addresses} (the "DNS" of the suites). */
    public void resolve(String host, InetAddress... addresses) {
        names.put(host, List.of(addresses));
    }

    public HostResolver resolver() {
        return host -> {
            List<InetAddress> found = names.get(host);
            if (found == null) {
                throw new UnknownHostException(host);
            }
            return found;
        };
    }

    /** Connects only the pinned documentation address to the container; any other address fails to connect. */
    public SocketDialer dialer() {
        return (address, connectTimeoutMillis) -> {
            dialled.add(address);
            if (!address.getAddress().equals(PINNED) || address.getPort() != PORT) {
                throw new java.net.ConnectException("the test network has no route to " + address);
            }
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(container.getHost(), container.getMappedPort(PORT)),
                    connectTimeoutMillis);
            return socket;
        };
    }

    /** A dialer whose every connection is refused (the "destination down" fault). */
    public static SocketDialer refusingDialer() {
        return (address, connectTimeoutMillis) -> {
            throw new java.net.ConnectException("connection refused (test fault) " + address);
        };
    }

    public List<InetSocketAddress> dialled() {
        return List.copyOf(dialled);
    }

    public void clearDialled() {
        dialled.clear();
    }

    // ------------------------------------------------------------------ the admin REST API

    /** Clears every stub and the request journal. */
    public void reset() {
        admin("POST", "/__admin/reset", "");
    }

    /** A GET stub on {@code path} answering {@code status} with the extra JSON response members (may be empty). */
    public void stubGet(String path, int status, String extraResponseJson) {
        String extra = extraResponseJson == null || extraResponseJson.isBlank() ? "" : "," + extraResponseJson;
        admin("POST", "/__admin/mappings", "{\"request\":{\"method\":\"GET\",\"url\":\"" + path + "\"},"
                + "\"response\":{\"status\":" + status + extra + "}}");
    }

    /** A redirect stub: {@code status} with a Location header. */
    public void stubRedirect(String path, int status, String location) {
        stubGet(path, status, "\"headers\":{\"Location\":\"" + location + "\"}");
    }

    /** How many requests WireMock received, on any path. */
    public int requestCount() {
        String answer = admin("POST", "/__admin/requests/count", "{\"method\":\"ANY\",\"urlPattern\":\".*\"}");
        Matcher matcher = COUNT.matcher(answer);
        if (!matcher.find()) {
            throw new IllegalStateException("WireMock count answer without a count: " + answer);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private String admin(String method, String path, String body) {
        URI uri = URI.create("http://" + container.getHost() + ":" + container.getMappedPort(PORT) + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = ADMIN.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("WireMock admin " + method + " " + path + " answered "
                        + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("WireMock admin call failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
