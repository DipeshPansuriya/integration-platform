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
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    /** Opened by {@link #stop()}: a hanging stub holds every call until then. */
    private final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
    private final boolean hangs;
    private final long delayMillis;

    private TargetStub(String path, int status) {
        this(path, status, false, 0);
    }

    private TargetStub(String path, int status, boolean hangs, long delayMillis) {
        this.path = path;
        this.status = status;
        this.hangs = hangs;
        this.delayMillis = delayMillis;
        server().createContext(path, exchange -> {
            calls.incrementAndGet();
            inFlight.incrementAndGet();
            try {
                if (hangs) {
                    released.await(); // never answers while the test runs (a hung target, E9-C9)
                    exchange.close();
                    return;
                }
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            } finally {
                inFlight.decrementAndGet();
            }
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
            // one thread per open call: a hanging stub holds its threads, and must never starve another stub's calls
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "target-stub");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
        }
        return server;
    }

    /** A stub answering {@code status} on {@code path}. */
    public static TargetStub answering(String path, int status) {
        return new TargetStub(path, status);
    }

    /** A stub that accepts the call and never answers until {@link #stop()} (a hanging target). */
    public static TargetStub hanging(String path) {
        return new TargetStub(path, 200, true, 0);
    }

    /** A stub answering {@code status} on {@code path} after {@code delayMillis}. */
    public static TargetStub answeringAfter(String path, int status, long delayMillis) {
        return new TargetStub(path, status, false, delayMillis);
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
        return calls.get();
    }

    /** Calls accepted and not yet answered (a hanging stub: every call that arrived). */
    public int inFlight() {
        return inFlight.get();
    }

    public void stop() {
        released.countDown();
        server().removeContext(path);
    }
}
