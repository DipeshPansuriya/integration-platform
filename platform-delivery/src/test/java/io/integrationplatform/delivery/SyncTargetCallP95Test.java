package io.integrationplatform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.domain.delivery.DeliveryState;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-127 (E10-F1-S4, the figure the E10 plan owns): the platform-side synchronous target-call sequence - the
 * persisted claim, the one synchronous HTTP call to a target answering in 300 ms, the persisted outcome -
 * measured ONE THOUSAND times against real PostgreSQL; the p95 must stay under 2 s. The target here is the JDK
 * built-in HTTP server at a fixed 300 ms delay (stand-in for the WireMock target answer; the WireMock descriptor
 * sits in platform-config, which is not a dependency of this module and poms are not touched).
 */
@Tag("container")
class SyncTargetCallP95Test {

    /** The synchronous target-call budget (PRD.md:161). */
    static final long BUDGET_MILLIS = 2_000;

    private static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS);

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private HikariDataSource pool;
    private HttpServer target;

    @BeforeEach
    void setUp() throws IOException {
        pool = db.appPool(16);
        target = targetAnsweringIn(Duration.ofMillis(300));
        target.start();
    }

    @AfterEach
    void tearDown() {
        target.stop(0);
        pool.close();
    }

    /** A plain HTTP target that answers 200 after the given delay - the scenario's "target answering in 300 ms". */
    static HttpServer targetAnsweringIn(Duration delay) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        // the JDK server's default executor is serial: without this the 300 ms answers queue instead of run
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/", exchange -> answer(exchange, delay));
        return server;
    }

    private static void answer(HttpExchange exchange, Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
            byte[] body = "{\"answer\":\"ok\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.close();
        } catch (IOException broken) {
            exchange.close();
        }
    }

    /**
     * One synchronous dispatch: create, claim, the target call, the persisted outcome - the shape ADR-011
     * Decision 5's inline path performs on the request thread, the pieces the adapters ride. Returns the
     * delivery's id and the whole call's wall time in ms.
     */
    private record Dispatch(UUID delivery, long millis) {
    }

    private Dispatch oneSyncCall(JdbcDeliveryRepository repository, int sequence)
            throws IOException, InterruptedException {
        UUID delivery = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        Instant at = DAY.plus(12, ChronoUnit.HOURS);
        tx().inTenantTx(DeliveryTestDatabase.TENANT_A, () ->
                repository.create(delivery, DeliveryTestDatabase.INTERFACE_ID, "p95-" + sequence,
                        (long) sequence, DeliveryTestDatabase.TARGET_ID, at));
        long started = System.nanoTime();

        tx().inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository.claimSend(delivery, token, at));

        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://" + target.getAddress().getHostName()
                            + ":" + target.getAddress().getPort() + "/"))
                    .header("Idempotency-Key", delivery.toString())
                    .timeout(Duration.ofSeconds(5))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }

        tx().inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository.markDelivered(delivery, token, at));

        return new Dispatch(delivery, (System.nanoTime() - started) / 1_000_000);
    }

    private DeliveryTestDatabase.DeliveryTx tx() {
        return DeliveryTestDatabase.tx(pool);
    }

    private JdbcDeliveryRepository repository() {
        return new JdbcDeliveryRepository(JdbcClient.create(pool),
                DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A));
    }

    @Test
    void syncTargetCallP95StaysUnderTwoSecondsOnAThousandCalls() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Future<Dispatch>> timed = new ArrayList<>();
            for (int call = 0; call < 1_000; call++) {
                final int sequence = call;
                timed.add(executor.submit((Callable<Dispatch>) () -> oneSyncCall(repository(), sequence)));
            }
            List<Long> millis = new ArrayList<>();
            List<UUID> deliveries = new ArrayList<>();
            for (Future<Dispatch> time : timed) {
                Dispatch dispatch = time.get(30, TimeUnit.SECONDS);
                millis.add(dispatch.millis());
                deliveries.add(dispatch.delivery());
            }
            long p95 = percentile(millis, 95);
            System.out.println("T-127 sync target-call p95 = " + p95 + " ms over " + millis.size() + " calls");

            assertThat(millis.size()).isEqualTo(1_000);
            assertThat(p95).as("sync target-call p95").isLessThan(BUDGET_MILLIS);
            // the rows equal the report: every call ended one state - DELIVERED on its own token
            tx().inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
                JdbcDeliveryRepository repository = repository();
                List<UUID> finalDeliveries = deliveries;
                for (UUID delivery : finalDeliveries) {
                    assertThat(repository.state(delivery)).contains(DeliveryState.DELIVERED);
                }
                return null;
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void overBudgetFailsTheTest() {
        assertThatThrownBy(() -> budgetExceeded(percentile(List.of(BUDGET_MILLIS, 2_001L), 95)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("sync target-call p95");
    }

    /** The budget check both the figure and the negative scenario share. */
    static void budgetExceeded(long p95Millis) {
        assertThat(p95Millis).as("sync target-call p95").isLessThan(BUDGET_MILLIS);
    }

    static long percentile(List<Long> values, int percentile) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1);
        return sorted.get(index);
    }
}