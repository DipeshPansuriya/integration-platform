package io.integrationplatform.e18;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

/**
 * E18 (T-151/T-152): the API partner, in-process on the loopback address. Unlike the E9/E11 stubs it can be taken
 * DOWN and brought back on the SAME port: {@link #down()} stops the listener, so a connect is refused (a real
 * outage, not a 503), {@link #up()} listens again. Every request is recorded with its per-delivery Idempotency-Key
 * and traceparent, so the suite can prove "exactly once" and "the trace id was carried".
 */
final class E18TargetStub {

    /** One request the partner received. */
    record Received(String path, String body, String idempotencyKey, String traceparent) {
    }

    private final int port;
    private HttpServer server;
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();

    E18TargetStub() {
        this.server = bind(0);
        this.port = server.getAddress().getPort();
    }

    int port() {
        return port;
    }

    /** Refuses every connection until {@link #up()} (the listener is closed, the port stays reserved by us). */
    synchronized void down() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Listens again on the same port. */
    synchronized void up() {
        if (server == null) {
            server = bind(port);
        }
    }

    synchronized boolean isUp() {
        return server != null;
    }

    void answer(String path, int status) {
        statusByPath.put(path, status);
    }

    List<Received> receivedOn(String path) {
        return received.stream().filter(r -> r.path().equals(path)).toList();
    }

    /** Distinct Idempotency-Keys the partner ACCEPTED (answered 2xx) on the path; a repeat of a key is a duplicate. */
    List<String> acceptedKeysOn(String path) {
        return received.stream().filter(r -> r.path().equals(path)).map(Received::idempotencyKey).toList();
    }

    void stop() {
        down();
    }

    private HttpServer bind(int at) {
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), at), 64);
            created.setExecutor(Executors.newFixedThreadPool(8));
            created.createContext("/", this::handle);
            created.start();
            return created;
        } catch (IOException e) {
            throw new IllegalStateException("the E18 target stub could not bind loopback port " + at, e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            int status = statusByPath.getOrDefault(path, 200);
            if (status < 300) {
                received.add(new Received(path, body, exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                        exchange.getRequestHeaders().getFirst("traceparent")));
            }
            byte[] answer = (status < 300 ? "{\"accepted\":true}" : "{\"error\":\"refused by the test target\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        }
    }
}
