package io.integrationplatform.app.audit;

import static io.integrationplatform.app.audit.AuditContainerSupport.TENANT_A;
import static io.integrationplatform.app.audit.AuditContainerSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.domain.audit.AuditEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Row 544 (option A, L3): the relay through {@code audit_relay_move} loses nothing and duplicates nothing. Events
 * appended to the outbox for two tenants are moved by the real {@link JdbcAuditEventStore#relayBound} (the statement
 * the relay loop runs per tenant) exactly once with identical fields; a tenant whose batch fails (a poison ledger row)
 * keeps its rows pending and does not block the other tenant; two concurrent relays on one tenant leave no
 * duplicate; the time of a 200-row batch is printed (no threshold invented).
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditRelayDeliveryContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static PlatformTestWiring.RestrictedLogin login;
    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    @Autowired
    private JdbcAuditEventStore store;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AuditContainerSupport.BoundTenant boundTenant;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @TestConfiguration
    static class TenantBindingConfiguration {

        @Bean
        @Primary
        AuditContainerSupport.BoundTenant boundTenantContext() {
            return new AuditContainerSupport.BoundTenant();
        }
    }

    @BeforeEach
    void seed() throws SQLException {
        if (SEEDED.compareAndSet(false, true)) {
            AuditContainerSupport.seed(POSTGRES, login);
        }
    }

    private <T> T inTenant(UUID tenant, Callable<T> work) {
        boundTenant.bind(tenant);
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                try {
                    return work.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            boundTenant.unbind();
        }
    }

    private void append(UUID tenant, String action, int count) {
        inTenant(tenant, () -> {
            for (int i = 0; i < count; i++) {
                store.append(new AuditEvent(tenant, "actor-" + i, action, List.of("k:" + i), "{\"b\":" + i + "}",
                        i % 2 == 0 ? null : "{\"a\":" + i + "}", Instant.parse("2026-02-03T04:05:06Z")));
            }
            return null;
        });
    }

    private int relay(UUID tenant, int batch) {
        Integer moved = inTenant(tenant, () -> store.relayBound(batch));
        return moved == null ? 0 : moved;
    }

    private long ledger(UUID tenant, String action) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            return AuditContainerSupport.count(owner,
                    "select count(*) from public.audit_event where tenant_id = ?::uuid and action = ?",
                    tenant.toString(), action);
        }
    }

    private long pending(UUID tenant, String action) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            return AuditContainerSupport.count(owner,
                    "select count(*) from public.audit_outbox where tenant_id = ?::uuid and action = ?"
                            + " and relayed_at is null", tenant.toString(), action);
        }
    }

    @Test
    void everyEventOfTwoTenantsIsDeliveredExactlyOnceWithIdenticalFields() throws SQLException {
        append(TENANT_A, "DLV_TWO", 30);
        append(TENANT_B, "DLV_TWO", 20);

        assertThat(relay(TENANT_A, 200)).isEqualTo(30);
        assertThat(relay(TENANT_B, 200)).isEqualTo(20);
        assertThat(relay(TENANT_A, 200)).as("a second cycle moves nothing").isZero();

        assertThat(ledger(TENANT_A, "DLV_TWO")).isEqualTo(30);
        assertThat(ledger(TENANT_B, "DLV_TWO")).isEqualTo(20);
        assertThat(pending(TENANT_A, "DLV_TWO") + pending(TENANT_B, "DLV_TWO")).isZero();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            assertThat(AuditContainerSupport.count(owner,
                    "select count(*) from public.audit_event e join public.audit_outbox o on o.action = e.action"
                            + " and o.tenant_id = e.tenant_id and o.actor = e.actor and o.target_ids = e.target_ids"
                            + " and o.before_state is not distinct from e.before_state"
                            + " and o.after_state is not distinct from e.after_state and o.event_at = e.event_at"
                            + " where e.action = 'DLV_TWO'")).as("each ledger row equals its outbox row").isEqualTo(50);
        }
    }

    @Test
    void aTenantWhoseBatchFailsKeepsItsRowsPendingAndDoesNotBlockTheOtherTenant() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            sql.execute("alter table public.audit_event add constraint row544_poison check (action <> 'DLV_POISON')");
        }
        try {
            append(TENANT_A, "DLV_POISON", 3);
            append(TENANT_B, "DLV_OK", 3);
            List<String> failed = new ArrayList<>();
            int moved = 0;
            for (UUID tenant : List.of(TENANT_A, TENANT_B)) { // the relay loop's per-tenant cycle
                try {
                    moved += relay(tenant, 200);
                } catch (RuntimeException e) {
                    failed.add(tenant.toString());
                }
            }

            assertThat(failed).containsExactly(TENANT_A.toString());
            assertThat(moved).isEqualTo(3);
            assertThat(pending(TENANT_A, "DLV_POISON")).as("the failing tenant's rows wait").isEqualTo(3);
            assertThat(ledger(TENANT_A, "DLV_POISON")).isZero();
            assertThat(ledger(TENANT_B, "DLV_OK")).isEqualTo(3);
        } finally {
            try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql =
                    owner.createStatement()) {
                sql.execute("alter table public.audit_event drop constraint if exists row544_poison");
            }
        }
    }

    @Test
    void twoConcurrentRelaysOnOneTenantLeaveNoDuplicateAndNoLoss() throws Exception {
        append(TENANT_A, "DLV_CONCURRENT", 100);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return relay(TENANT_A, 200);
                }));
            }
            go.countDown();
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get();
            }

            assertThat(total).as("each row moved by exactly one of the two relays").isEqualTo(100);
            assertThat(ledger(TENANT_A, "DLV_CONCURRENT")).isEqualTo(100);
            assertThat(pending(TENANT_A, "DLV_CONCURRENT")).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTwoHundredRowBatchTimeIsMeasuredAndPrinted() throws SQLException {
        append(TENANT_B, "DLV_TIMING", 200);

        long started = System.nanoTime();
        int moved = relay(TENANT_B, 200);
        long millis = (System.nanoTime() - started) / 1_000_000;

        System.out.println("ROW544-MEASURE relayBound(200) in DEFINER mode moved " + moved + " rows in " + millis + " ms");
        assertThat(moved).isEqualTo(200);
        assertThat(ledger(TENANT_B, "DLV_TIMING")).isEqualTo(200);
    }
}
