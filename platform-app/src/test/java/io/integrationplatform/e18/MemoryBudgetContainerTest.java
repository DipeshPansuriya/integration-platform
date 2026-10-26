package io.integrationplatform.e18;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.identity.KeycloakRealmContainer.ClientCredentials;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T-153 (E18-F1-S3): a bounded load with the application's heap, connection pool and queues sampled while it runs,
 * judged against {@link MemoryBudget} (recorded in {@code e2e-full-budget.properties}).
 *
 * <p><b>Load, stated:</b> 200 messages from 4 parallel posters (50 each, one token per poster) through the whole
 * pipeline to an API partner, one message every key (PER_KEY ordering, so 200 keys), the fork at {@code -Xmx1g}.
 * <b>Sampled</b> every 200 ms with {@code Runtime} (heap used = total - free) and the Hikari pool MXBean of the
 * application's DataSource (active / pending / max), plus once a second the queue backlog (unpublished outbox rows +
 * unsettled deliveries). <b>Not proven:</b> a production-size load, off-heap/native memory, or the memory of the
 * containers (Docker's, not this JVM's).
 */
@DisplayName("E18-F1-S3 the suite has a measured memory budget (T-153)")
class MemoryBudgetContainerTest extends E18Base {

    private static final int MESSAGES = 200;
    private static final int POSTERS = 4;

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("a bounded load keeps heap, pool and queue within the recorded budget")
    void boundedLoadStaysWithinTheMemoryBudget() throws Exception {
        grantAppRoleOnce();
        MemoryBudget budget = MemoryBudget.load();
        String path = "/memory-budget/orders";
        ClientCredentials source = newSource("e18-mem");
        String url = "http://api.e18.test:" + PARTNER.port() + path;
        E18Seed.Tenant tenant;
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            tenant = E18Seed.onboard(owner, "mem", "CUST-MEM", source.clientId(), KEYCLOAK.issuerUri(), "API",
                    "{\"baseUrl\":\"" + url + "\"}", url, true);
        }
        PARTNER.up();
        HikariPoolMXBean pool = dataSource.isWrapperFor(HikariDataSource.class)
                ? dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean() : null;
        int poolMax = dataSource.isWrapperFor(HikariDataSource.class)
                ? dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize() : -1;

        long baseline = usedMiB(true);
        AtomicLong heapPeak = new AtomicLong(baseline);
        AtomicInteger poolActivePeak = new AtomicInteger();
        AtomicInteger poolPendingPeak = new AtomicInteger();
        AtomicLong backlogPeak = new AtomicLong();
        Thread sampler = new Thread(() -> {
            long lastBacklog = 0;
            while (!Thread.currentThread().isInterrupted()) {
                heapPeak.accumulateAndGet(usedMiB(false), Math::max);
                if (pool != null) {
                    poolActivePeak.accumulateAndGet(pool.getActiveConnections(), Math::max);
                    poolPendingPeak.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                }
                if (System.nanoTime() - lastBacklog > 1_000_000_000L) {
                    lastBacklog = System.nanoTime();
                    backlogPeak.accumulateAndGet(backlog(tenant), Math::max);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "e18-memory-sampler");
        sampler.setDaemon(true);
        sampler.start();

        long drainStart;
        try {
            ExecutorService posters = Executors.newFixedThreadPool(POSTERS);
            List<Future<?>> done = new ArrayList<>();
            for (int poster = 0; poster < POSTERS; poster++) {
                int from = poster * (MESSAGES / POSTERS);
                String token = KEYCLOAK.clientCredentialsToken(source.clientId(), source.secret());
                done.add(posters.submit(() -> {
                    for (int i = from; i < from + MESSAGES / POSTERS; i++) {
                        postWith(token, "{\"customerId\":\"CUST-MEM\",\"orderId\":\"ORD-MEM-" + i + "\",\"amount\":"
                                + i + "}");
                    }
                    return null;
                }));
            }
            for (Future<?> poster : done) {
                poster.get(5, java.util.concurrent.TimeUnit.MINUTES);
            }
            posters.shutdown();
            drainStart = System.nanoTime();
            await("all " + MESSAGES + " deliveries to settle (backlog to drain)", Duration.ofSeconds(300),
                    () -> backlog(tenant) == 0 && ownerLong("select count(*) from public.delivery where tenant_id ="
                            + " ?::uuid", tenant.id()) == MESSAGES);
        } finally {
            sampler.interrupt();
        }
        long drainSeconds = (System.nanoTime() - drainStart) / 1_000_000_000L;
        long bulkheadFullDeferrals = ownerLong("select count(*) from public.delivery_event where tenant_id ="
                + " ?::uuid and type = 'BULKHEAD_FULL'", tenant.id()); // F: reported, never budgeted
        long retained = usedMiB(true) - baseline;

        MemoryBudget.Measurement measured = new MemoryBudget.Measurement(heapPeak.get(), Math.max(0, retained),
                poolPendingPeak.get(), poolActivePeak.get(), poolMax, backlogPeak.get(), drainSeconds);
        String line = "memory: load " + MESSAGES + " msgs / " + POSTERS + " posters, baseline " + baseline + " MiB, "
                + measured + ", budget " + budget + ", -Xmx " + Runtime.getRuntime().maxMemory() / (1024 * 1024)
                + " MiB, BULKHEAD_FULL deferrals " + bulkheadFullDeferrals;
        java.nio.file.Path notes = E18Outcomes.FILE.resolveSibling("notes.txt");
        Files.createDirectories(notes.getParent());
        Files.writeString(notes, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        System.out.println("E18 NOTE " + line);

        assertThat(PARTNER.acceptedKeysOn(path)).as("every message delivered exactly once under load")
                .hasSize(MESSAGES).doesNotHaveDuplicates();
        budget.enforce(measured);
    }

    private void postWith(String token, String body) throws Exception {
        HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(
                        backend().resolve("/api/v1/interfaces/" + E18Seed.INTERFACE_CODE + "/messages"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("X-Source-Contract-Version", E18Seed.SOURCE_CONTRACT_LABEL)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).as("intake under load: %s", answer.body()).isEqualTo(202);
    }

    /** Outbox rows not yet published + deliveries not yet settled, for the tenant. */
    private static long backlog(E18Seed.Tenant tenant) {
        return ownerLong("select (select count(*) from public.outbox where tenant_id = ?::uuid and published_at is"
                + " null) + (select count(*) from public.delivery where tenant_id = ?::uuid and state not in"
                + " ('DELIVERED', 'DEAD_LETTERED'))", tenant.id(), tenant.id());
    }

    private static long usedMiB(boolean collectFirst) {
        if (collectFirst) {
            System.gc();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }
}
