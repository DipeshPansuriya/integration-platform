package io.integrationplatform.delivery.e9;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryReconcileStore;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.port.DeliveryReconcileStore.SendingClaim;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-180 (E9-F3-S1): the delivery side of the reconciler sweep on the real tables - the stale {@code SENDING}
 * claims older than a cutoff (with the token their settlement must carry), and the {@code HELD} deliveries whose
 * same-scope predecessors are all final (the release a final state should have made, ADR-011 Decision 6) - both
 * scoped to the bound tenant.
 */
@Tag("container")
class DeliveryReconcileContainerTest {

    private static final UUID TENANT_A = DeliveryTestDatabase.TENANT_A;
    private static final UUID TENANT_B = DeliveryTestDatabase.TENANT_B;

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private DeliveryTestDatabase.DeliveryTx tx;

    @BeforeEach
    void setUp() {
        pool = db.appPool(4);
        jdbc = JdbcClient.create(pool);
        tx = DeliveryTestDatabase.tx(pool);
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    @Test
    void onlyClaimsAtOrBeforeTheCutoffAreStaleAndTheyCarryTheirToken() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String key = "stale-" + UUID.randomUUID();
        UUID stale = newDelivery(TENANT_A, key, null, now.minus(Duration.ofHours(1)));
        UUID fresh = newDelivery(TENANT_A, key, null, now.minus(Duration.ofHours(1)));
        UUID staleToken = claim(TENANT_A, stale, now.minus(Duration.ofMinutes(30)));
        claim(TENANT_A, fresh, now);
        UUID foreign = newDelivery(TENANT_B, key, null, now.minus(Duration.ofHours(1)));
        claim(TENANT_B, foreign, now.minus(Duration.ofMinutes(30)));

        List<SendingClaim> claims = tx.inTenantTx(TENANT_A,
                () -> store(TENANT_A).sendingClaimedBefore(now.minus(Duration.ofMinutes(10)), 1000));

        assertThat(claims).filteredOn(c -> List.of(stale, fresh, foreign).contains(c.deliveryId()))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.deliveryId()).isEqualTo(stale);
                    assertThat(c.claimToken()).isEqualTo(staleToken);
                    assertThat(c.claimedAt()).isEqualTo(now.minus(Duration.ofMinutes(30)));
                    assertThat(c.targetId()).isEqualTo(DeliveryTestDatabase.TARGET_ID);
                    assertThat(c.attempts()).isZero();
                });
    }

    @Test
    void aHeldDeliveryWhosePredecessorIsFinalIsReleasableAndOneBehindANonFinalOneIsNot() {
        Instant now = Instant.now();
        String finalKey = "final-" + UUID.randomUUID();
        UUID predecessor = newDelivery(TENANT_A, finalKey, 1L, now);
        tx.inTenantTx(TENANT_A, () -> repository(TENANT_A).deadLetter(predecessor,
                claim(TENANT_A, predecessor, now), OutcomeClass.PERMANENT, now));
        UUID missedRelease = newDelivery(TENANT_A, finalKey, 2L, now);
        hold(TENANT_A, missedRelease, now);

        String openKey = "open-" + UUID.randomUUID();
        newDelivery(TENANT_A, openKey, 1L, now); // PENDING: not final
        UUID blocked = newDelivery(TENANT_A, openKey, 2L, now);
        hold(TENANT_A, blocked, now);

        UUID foreignPredecessor = newDelivery(TENANT_B, finalKey, 1L, now);
        tx.inTenantTx(TENANT_B, () -> repository(TENANT_B).deadLetter(foreignPredecessor,
                claim(TENANT_B, foreignPredecessor, now), OutcomeClass.PERMANENT, now));
        UUID foreignHeld = newDelivery(TENANT_B, finalKey, 2L, now);
        hold(TENANT_B, foreignHeld, now);

        List<UUID> releasable = tx.inTenantTx(TENANT_A, () -> store(TENANT_A).lockReleasableHeld(1000));

        assertThat(releasable).contains(missedRelease).doesNotContain(blocked, foreignHeld);
    }

    @Test
    void releaseMovesAHeldDeliveryToDueOnceWithItsReleaseEvent() {
        Instant now = Instant.now();
        UUID held = newDelivery(TENANT_A, "release-" + UUID.randomUUID(), 1L, now);
        hold(TENANT_A, held, now);

        boolean first = tx.inTenantTx(TENANT_A, () -> store(TENANT_A).release(held));
        boolean second = tx.inTenantTx(TENANT_A, () -> store(TENANT_A).release(held));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(tx.inTenantTx(TENANT_A, () -> repository(TENANT_A).state(held))).contains(DeliveryState.DUE);
        assertThat(tx.inTenantTx(TENANT_A, () -> repository(TENANT_A).countEvents(held,
                DeliveryEventType.RELEASE))).isEqualTo(1);
    }

    // ---------- helpers ----------

    private JdbcDeliveryReconcileStore store(UUID tenant) {
        return new JdbcDeliveryReconcileStore(jdbc, DeliveryTestDatabase.tenant(tenant));
    }

    private JdbcDeliveryRepository repository(UUID tenant) {
        return new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(tenant));
    }

    private UUID newDelivery(UUID tenant, String businessKey, Long keySeq, Instant at) {
        UUID id = UUID.randomUUID();
        tx.inTenantTx(tenant, () -> repository(tenant).create(id, DeliveryTestDatabase.INTERFACE_ID, businessKey,
                keySeq, DeliveryTestDatabase.TARGET_ID, at));
        return id;
    }

    private UUID claim(UUID tenant, UUID id, Instant at) {
        UUID token = UUID.randomUUID();
        tx.inTenantTx(tenant, () -> repository(tenant).claimSend(id, token, at));
        return token;
    }

    private void hold(UUID tenant, UUID id, Instant at) {
        tx.inTenantTx(tenant, () -> repository(tenant).hold(id, at));
    }
}
