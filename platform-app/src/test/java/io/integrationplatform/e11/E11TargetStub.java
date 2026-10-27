package io.integrationplatform.e11;

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

/**
 * The targets of the two-target orders example (T-132), in-process: one plain HTTP server on the loopback address that every
 * target endpoint of the suite reaches through the suite's test network (the {@code *.e11.test} names resolve to a
 * TEST-NET-3 documentation address the destination guard accepts, and the suite's dialer connects only that pinned
 * address to this server). Each target endpoint has its own path; each path answers its configured status and every
 * request is recorded - path, body and the per-delivery Idempotency-Key - so the suite can assert exactly what each
 * target received. Nothing here leaves the machine.
 */
final class E11TargetStub {

    /** One request a target received. */
    record Received(String path, String body, String idempotencyKey, String contentType) {
    }

    private final HttpServer server;
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();

    private E11TargetStub(HttpServer server) {
        this.server = server;
    }

    static E11TargetStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
            E11TargetStub stub = new E11TargetStub(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("the E11 target stub could not bind a loopback port", e);
        }
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** The status the path answers (200 when never set). */
    void answer(String path, int status) {
        statusByPath.put(path, status);
    }

    List<Received> receivedOn(String path) {
        return received.stream().filter(r -> r.path().equals(path)).toList();
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            received.add(new Received(path, body, exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            int status = statusByPath.getOrDefault(path, 200);
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
