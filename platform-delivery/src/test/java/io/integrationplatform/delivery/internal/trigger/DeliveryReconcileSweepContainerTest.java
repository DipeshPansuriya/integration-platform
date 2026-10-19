package io.integrationplatform.delivery.internal.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryReconcileStore;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.delivery.port.InstanceReadiness;
import io.integrationplatform.delivery.port.TargetRedriveCap;
import io.integrationplatform.delivery.port.TargetSendFacts;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.port.OutboxEntry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-180 (ADR-010 section 6) on the real delivery tables, through the real delivery-retry trigger: a stale SENDING
 * claim is settled as AMBIGUOUS (never re-sent), the stale threshold is exactly {@code 3 x timeout + 5 s + 60 s},
 * and a PENDING/DUE delivery whose hand-off was lost (no unpublished outbox row, unchanged 5 minutes) is re-handed.
 * Each test uses its own tenant so the shared database's other deliveries cannot change the counts.
 */
@Tag("container")
class DeliveryReconcileSweepContainerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30); // stale limit 3 x 30 + 5 + 60 = 155 s

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private final UUID tenant = UUID.randomUUID();
    private final TriggerTenantContext context = new TriggerTenantContext();
    private final List<OutboxEntry> handed = new CopyOnWriteArrayList<>();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection owner = db.owner(); PreparedStatement insert = owner.prepareStatement(
                "insert into public.tenant (id, code, status, time_zone) values (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            insert.setString(1, tenant.toString());
            insert.setString(2, "t180-" + tenant);
            insert.executeUpdate();
        }
        pool = db.appPool(2);
        jdbc = JdbcClient.create(pool);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(pool) {
            @Override
            protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                    throws SQLException {
                try (PreparedStatement stamp = connection.prepareStatement(
                        "select set_config('app.tenant_id', ?, true)")) {
                    stamp.setString(1, context.requireTenantId().toString());
                    stamp.execute();
                }
            }
        });
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    @Test
    @DisplayName("T-180 a: a stale SENDING claim is AMBIGUOUS - dead-lettered (non-idempotent) or RETRY_WAIT, never SENDING")
    void aStaleSendingClaimIsSettledAsAmbiguousNeverReSent() {
        Instant claimedAt = Instant.now().minus(Duration.ofMinutes(10));
        UUID nonIdempotent = sendingSince(claimedAt);

        trigger(false).runOnce();

        assertThat(state(nonIdempotent)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(deadLetterClass(nonIdempotent)).isEqualTo(OutcomeClass.AMBIGUOUS.name());
        assertThat(handed).as("never re-sent straight away").isEmpty();

        UUID idempotent = sendingSince(claimedAt);
        trigger(true).runOnce();

        assertThat(state(idempotent)).as("an idempotent target retries later, not now")
                .isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(handed).extracting(OutboxEntry::aggregateId).doesNotContain(idempotent);
    }

    @Test
    @DisplayName("T-180 c: stale exactly at 3 x timeout + 5 s + 60 s - just under stays SENDING, over is settled")
    void theStaleThresholdIsThreeTimeoutsPlusFiveSecondsPlusSixtySeconds() {
        assertThat(RetryPolicy.defaults().staleClaimLimit(TIMEOUT)).isEqualTo(Duration.ofSeconds(155));
        Instant now = Instant.now();
        // 10 s margin each side of 155 s: Instant.now() moves between the claim and the trigger's own clock
        UUID justUnder = sendingSince(now.minusSeconds(145));
        UUID justOver = sendingSince(now.minusSeconds(165));

        trigger(false).runOnce();

        assertThat(state(justUnder)).as("inside the limit: the send may yet report").isEqualTo(DeliveryState.SENDING);
        assertThat(state(justOver)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(deadLetterClass(justOver)).isEqualTo(OutcomeClass.AMBIGUOUS.name());
    }

    @Test
    @DisplayName("T-180 b: PENDING unchanged 5 min with no unpublished outbox row is re-handed; one with a row is not")
    void aLostHandOffIsReHandedButAPendingOneIsNot() {
        Instant sixMinutesAgo = Instant.now().minus(Duration.ofMinutes(6));
        UUID lost = pending(sixMinutesAgo);
        UUID stillQueued = pending(sixMinutesAgo);
        UUID recent = pending(Instant.now());
        inTenant(() -> jdbc.sql("insert into public.outbox (tenant_id, topic, aggregate_id, record_key, key_bucket)"
                        + " values (:tenant::uuid, 'platform.delivery.due', :id::uuid, :key, 0)")
                .params(Map.of("tenant", tenant.toString(), "id", stillQueued.toString(),
                        "key", "delivery:" + stillQueued))
                .update());

        trigger(false).runOnce();

        assertThat(handed).as("only the lost hand-off is re-handed").extracting(OutboxEntry::aggregateId)
                .containsExactly(lost);
        assertThat(handed.getFirst().recordKey()).isEqualTo("delivery:" + lost);
        assertThat(state(lost)).isEqualTo(DeliveryState.PENDING);
        assertThat(state(stillQueued)).isEqualTo(DeliveryState.PENDING);
        assertThat(state(recent)).isEqualTo(DeliveryState.PENDING);
    }

    private DeliveryRetryTrigger trigger(boolean idempotent) {
        return new DeliveryRetryTrigger(() -> List.of(tenant), context, transactions,
                new JdbcDeliveryRetryStore(jdbc, context), entry -> {
                    handed.add(entry);
                    return handed.size();
                }, 50, InstanceReadiness.alwaysReady(), deliveryId -> Optional.empty(),
                TargetRedriveCap.unlimited(), new JdbcDeliveryReconcileStore(jdbc, context),
                target -> Optional.of(new TargetSendFacts.Facts(TIMEOUT, idempotent)), RetryPolicy.defaults());
    }

    private UUID pending(Instant at) {
        UUID id = UUID.randomUUID();
        inTenant(() -> {
            repository().create(id, DeliveryTestDatabase.INTERFACE_ID, "t180-" + id, null,
                    DeliveryTestDatabase.TARGET_ID, at);
            return null;
        });
        return id;
    }

    private UUID sendingSince(Instant claimedAt) {
        UUID id = pending(claimedAt);
        inTenant(() -> repository().claimSend(id, UUID.randomUUID(), claimedAt));
        return id;
    }

    private DeliveryState state(UUID id) {
        return inTenant(() -> repository().state(id)).orElseThrow();
    }

    private String deadLetterClass(UUID id) {
        return inTenant(() -> jdbc.sql("select dead_letter_class from public.delivery where id = :id::uuid")
                .params(Map.of("id", id.toString())).query(String.class).single());
    }

    private JdbcDeliveryRepository repository() {
        return new JdbcDeliveryRepository(jdbc, context);
    }

    private <T> T inTenant(java.util.function.Supplier<T> work) {
        context.bind(tenant);
        try {
            return transactions.execute(status -> work.get());
        } finally {
            context.unbind();
        }
    }
}
