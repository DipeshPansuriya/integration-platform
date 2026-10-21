package io.integrationplatform.e9.isolation;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * The target stub without a new test dependency (the JDK's own HTTP server): one shared loopback server, one
 * context per stub answering a fixed status and counting its calls. Endpoint rows point at the test-only host
 * {@value #HOST}; {@link E9TestNetwork} resolves it to a pinned address and dials {@link #port()} - the destination
 * guard is never relaxed.
 */
public final class TargetStub {

    public static final String HOST = "targets.e9.test";
    public static final int PINNED_PORT = 8080;

    private static HttpServer server;

    final String path;
    final int status;
    volatile int calls;

    private TargetStub(String path, int status) {
        this.path = path;
        this.status = status;
        server().createContext(path, exchange -> {
            calls++;
            byte[] body = ("stub-" + path).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private static synchronized HttpServer server() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
        }
        return server;
    }

    /** A stub answering {@code status} on {@code path}. */
    public static TargetStub answering(String path, int status) {
        return new TargetStub(path, status);
    }

    /** The loopback port the test network dials the pinned address to. */
    public static int port() {
        return server().getAddress().getPort();
    }

    /** The base URL the target_endpoint row points stage 2's adapter at. */
    public String baseUrl() {
        return "http://" + HOST + ":" + PINNED_PORT + path;
    }

    public int calls() {
        return calls;
    }

    public void stop() {
        server().removeContext(path);
    }
}
