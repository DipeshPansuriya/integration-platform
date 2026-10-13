package io.integrationplatform.routing.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryClaimService;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.delivery.internal.retry.OutcomeRecorder;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.claim.DeliveryClaimHandler;
import io.integrationplatform.routing.stage2.LayerOneRedeliveryTest.TransientTarget;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E9-F1-S2 / T-119 on the real tables (amit-developer, lane A1): the claim stays exactly the existing
 * {@link DeliveryClaimHandler} path (PENDING -> SENDING with a fresh token), the redelivering send runs the
 * one Camel attempt, and every call (the first try and each redelivery) appends exactly one
 * {@code delivery_attempt} row with layer 1 to lane C's append-only ledger - "the report equals the rows":
 * 3 calls -> 3 rows. The recording binds to the real delivery ledger the way integration wires it.
 */
@Tag("container")
class LayerOneAttemptRowsContainerTest {

    private static final UUID TENANT = RoutingTestDatabase.TENANT_A;
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private static RoutingTestDatabase db;
    private static HikariDataSource pool;
    private static JdbcClient jdbc;
    private static RoutingTestDatabase.TenantTx tx;

    private DefaultCamelContext context;

    @BeforeAll
    static void database() {
        db = RoutingTestDatabase.get();
        pool = db.appPool(4);
        jdbc = JdbcClient.create(pool);
        tx = new RoutingTestDatabase.TenantTx(pool);
    }

    @AfterAll
    static void closePool() {
        pool.close();
    }

    @AfterEach
    void stopTheContext() throws Exception {
        if (context != null) {
            context.stop();
        }
    }

    /** The real adapters layer 1 records through at integration: the delivery ledger and the recorder path. */
    final class RealAttemptAdapters {

        private final JdbcDeliveryRepository repository;
        private final org.springframework.transaction.support.TransactionTemplate stamped;

        RealAttemptAdapters(JdbcDeliveryRepository repository) {
            this.repository = repository;
            this.stamped = tx.stamping(RoutingTestDatabase.tenant(TENANT));
        }

        Stage2AttemptWriter attempts() {
            return (deliveryId, layer, outcome, startedAt, finishedAt, detail) ->
                    stamped.execute(status -> {
                        repository.recordAttempt(deliveryId, layer, outcome, startedAt, finishedAt, detail);
                        return null;
                    });
        }

        Stage2OutcomeHandoff outcomeHandoff(UUID claimToken, int attemptsSoFar) {
            JdbcDeliveryRepository repo = repository;
            OutcomeRecorder recorder = new OutcomeRecorder(new JdbcDeliveryRetryStore(jdbc,
                    RoutingTestDatabase.tenant(TENANT)), RetryPolicy.defaults(), () -> 0.0);
            return (deliveryId, token, tries, outcome, targetDeclaresIdempotentReceive) -> stamped.execute(status -> {
                recorder.record(deliveryId, token, attemptsSoFar, outcome, Instant.now(), null,
                        targetDeclaresIdempotentReceive);
                return null;
            });
        }
    }

    @Test
    void threeCallsAppendExactlyThreeLayerOneAttemptRowsAndTheClaimStaysTheExistingPath() throws Exception {
        UUID delivery = UUID.nameUUIDFromBytes("t119-delivery".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID endpoint = UUID.nameUUIDFromBytes("t119-endpoint".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID claimToken = UUID.nameUUIDFromBytes("t119-claim".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        tx.inTenantTx(TENANT, () -> repository(TENANT).create(delivery, RoutingTestDatabase.INTERFACE_ID,
                "t119", null, endpoint, NOW));

        // the existing claim path, unchanged (T-193): PENDING -> SENDING with the fresh token
        DeliveryClaimHandler claimHandler = new DeliveryClaimHandler(tx.stamping(RoutingTestDatabase.tenant(TENANT)),
                new DeliveryClaimService(repository(TENANT)));
        DeliveryClaimService.Outcome claimed = claimHandler.claim(null, delivery, claimToken);
        assertThat(claimed).as("the claim path is the existing one, unchanged")
                .isEqualTo(DeliveryClaimService.Outcome.CLAIMED);

        RealAttemptAdapters real = new RealAttemptAdapters(repository(TENANT));
        TransientTarget target = new TransientTarget();
        target.failNext(2, OutcomeClass.TRANSIENT);
        List<UUID> handoffDeliveryIds = new ArrayList<>();
        context = new DefaultCamelContext();
        LayerOneRouteBuilder builder = new LayerOneRouteBuilder(target, real.attempts(),
                (deliveryId, token, tries, outcome, targetDeclaresIdempotentReceive) -> {
                    handoffDeliveryIds.add(deliveryId);
                }, LayerOneWaits.defaults());
        context.addRoutes(builder);
        context.start();

        context.createProducerTemplate().sendBody("direct:stage2-send",
                new Stage2Send(delivery, claimToken, null, endpoint, false));

        assertThat(target.calls()).as("three calls for two transient failures then success").isEqualTo(3);
        List<String> rows = tx.jdbcClient().sql(
                        "select layer || ':' || outcome_class from public.delivery_attempt"
                                + " where delivery_id = :id::uuid and tenant_id = :t::uuid order by id")
                .params(Map.of("id", delivery.toString(), "t", TENANT.toString()))
                .query(String.class).list();
        assertThat(rows).as("one append-only row per call, layer 1, in order (the report equals the rows)")
                .containsExactly("1:TRANSIENT", "1:TRANSIENT", "1:SUCCESS");
        assertThat(tx.jdbcClient().sql("select state from public.delivery where id = :id::uuid")
                .params(Map.of("id", delivery.toString())).query(String.class).single())
                .as("the delivery is still SENDING: layer 1 succeeded inside the one attempt, "
                        + "the classifier's DELIVERED move is not this test's slice")
                .isEqualTo("SENDING");
        assertThat(handoffDeliveryIds).as("success inside layer 1 hands off nothing").isEmpty();
    }

    @Test
    void anExhaustedTransientSeriesEndsInRetryWaitThroughTheRecorderPathOnTheRealTables() throws Exception {
        UUID delivery = UUID.nameUUIDFromBytes("t119-exhausted".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID endpoint = UUID.nameUUIDFromBytes("t119-endpoint2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID claimToken = UUID.nameUUIDFromBytes("t119-claim2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        tx.inTenantTx(TENANT, () -> repository(TENANT).create(delivery, RoutingTestDatabase.INTERFACE_ID,
                "t119-exhausted", null, endpoint, NOW));
        tx.inTenantTx(TENANT, () -> repository(TENANT).claimSend(delivery, claimToken, NOW));

        RealAttemptAdapters real = new RealAttemptAdapters(repository(TENANT));
        TransientTarget target = new TransientTarget();
        target.alwaysFail(OutcomeClass.TRANSIENT);
        context = new DefaultCamelContext();
        context.addRoutes(new LayerOneRouteBuilder(target, real.attempts(),
                real.outcomeHandoff(claimToken, 3), LayerOneWaits.defaults()));
        context.start();

        context.createProducerTemplate().sendBody("direct:stage2-send",
                new Stage2Send(delivery, claimToken, null, endpoint, false));

        assertThat(tx.jdbcClient().sql("select count(*)::int from public.delivery_attempt"
                        + " where delivery_id = :id::uuid and tenant_id = :t::uuid")
                .params(Map.of("id", delivery.toString(), "t", TENANT.toString())).query(Integer.class).single())
                .as("first try + 2 redeliveries: 3 rows, no more").isEqualTo(3);
        assertThat(tx.jdbcClient().sql("select state from public.delivery where id = :id::uuid")
                .params(Map.of("id", delivery.toString())).query(String.class).single())
                .as("layer 2 took over through the recorder path: RETRY_WAIT with a next_attempt_at")
                .isEqualTo("RETRY_WAIT");
    }

    private JdbcDeliveryRepository repository(UUID tenant) {
        return new JdbcDeliveryRepository(jdbc, RoutingTestDatabase.tenant(tenant));
    }
}