package io.integrationplatform.routing.e9.isolation;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpServer;

/**
 * The WireMock-equivalent target stub without a new test dependency (ponytail: the JDK's own HTTP server, no
 * pom change in a module whose pom lane D does not own): Target A answers 500 on every request, Target B answers
 * 200 - enough for E9-F1-S2's best-effort scenario where stage 2's adapter sends and reads exactly the status.
 * Both keep a request log so a green run can also prove who was called how often.
 */
public final class TargetStub {

    final HttpServer server;
    final String path;
    final int status;
    volatile int calls;

    private TargetStub(String path, int status) throws Exception {
        this.path = path;
        this.status = status;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext(
                path,
                exchange -> {
                    calls++;
                    byte[] body = ("stub-" + path).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        this.server.setExecutor(Executors.newFixedThreadPool(2));
        this.server.start();
    }

    /** A stub answering {@code status} on {@code path}. */
    public static TargetStub answering(String path, int status) throws Exception {
        return new TargetStub(path, status);
    }

    /** The base URL the target_endpoint row points stage 2's adapter at. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public int calls() {
        return calls;
    }

    public void stop() {
        server.stop(0);
    }
}