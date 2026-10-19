package io.integrationplatform.delivery.e13;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryDailyAggregateStore;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.port.DeliveryDailyAggregates.DailyCounts;
import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-138 (E13-F1-S2): the daily delivery pre-aggregate - per tenant / interface / target / day: successes, failures by
 * reason, retries made, retried-then-succeeded and still stuck - equals a direct count over delivery and
 * delivery_attempt, drifts visibly when a row lands after the roll-up, and is tenant-scoped under FORCE RLS.
 */
@Tag("container")
class DeliveryDailyAggregateContainerTest {

    private static final UUID TENANT_A = DeliveryTestDatabase.TENANT_A;
    private static final UUID TENANT_B = DeliveryTestDatabase.TENANT_B;
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private DeliveryTestDatabase.DeliveryTx tx;
    private final List<UUID> interfaces = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final List<UUID> targets = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID());
    private final List<UUID> deliveredOnce = new ArrayList<>();

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
    void theAggregateEqualsTheDirectCountForEveryInterfaceTargetAndDay() {
        seed();
        for (LocalDate day : List.of(YESTERDAY, TODAY)) {
            tx.inTenantTx(TENANT_A, () -> store(TENANT_A).refresh(day));

            List<DailyCounts> aggregated = mine(tx.inTenantTx(TENANT_A, () -> store(TENANT_A).aggregated(day)));
            List<DailyCounts> direct = mine(tx.inTenantTx(TENANT_A, () -> store(TENANT_A).direct(day)));

            assertThat(aggregated).hasSize(12).containsExactlyElementsOf(direct);
            assertThat(aggregated.stream().mapToLong(c -> c.successes() + c.failuresTransient()
                    + c.failuresAmbiguous() + c.failuresPermanent() + c.stillStuck()).sum()).isEqualTo(12);
            assertThat(aggregated.stream().mapToLong(DailyCounts::retriedThenSucceeded).sum()).isPositive();
            assertThat(aggregated.stream().mapToLong(DailyCounts::retriesMade).sum()).isPositive();
            assertThat(aggregated.stream().mapToLong(DailyCounts::failuresPermanent).sum()).isPositive();
            assertThat(aggregated.stream().mapToLong(DailyCounts::stillStuck).sum()).isPositive();
        }
    }

    @Test
    void anAttemptAfterTheRollUpShowsAsADifferenceUntilTheNextRefresh() {
        seed();
        tx.inTenantTx(TENANT_A, () -> store(TENANT_A).refresh(TODAY));
        UUID late = deliveredOnce.getLast();
        Instant now = Instant.now();
        tx.inTenantTx(TENANT_A, () -> repository(TENANT_A).recordAttempt(late, 1, OutcomeClass.TRANSIENT, now, now,
                "late"));

        assertThat(mine(tx.inTenantTx(TENANT_A, () -> store(TENANT_A).aggregated(TODAY))))
                .isNotEqualTo(mine(tx.inTenantTx(TENANT_A, () -> store(TENANT_A).direct(TODAY))));

        tx.inTenantTx(TENANT_A, () -> store(TENANT_A).refresh(TODAY));
        assertThat(mine(tx.inTenantTx(TENANT_A, () -> store(TENANT_A).aggregated(TODAY))))
                .isEqualTo(mine(tx.inTenantTx(TENANT_A, () -> store(TENANT_A).direct(TODAY))));
    }

    @Test
    void tenantBNeverSeesTenantARows() {
        seed();
        tx.inTenantTx(TENANT_A, () -> store(TENANT_A).refresh(TODAY));
        tx.inTenantTx(TENANT_B, () -> store(TENANT_B).refresh(TODAY));

        assertThat(mine(tx.inTenantTx(TENANT_A, () -> store(TENANT_A).aggregated(TODAY)))).hasSize(12);
        assertThat(mine(tx.inTenantTx(TENANT_B, () -> store(TENANT_B).aggregated(TODAY)))).isEmpty();
        // raw read, no tenant predicate: only FORCE RLS stands between tenant B and tenant A's rows
        Long visible = tx.inTenantTx(TENANT_B, () -> jdbc.sql(
                "select count(*) from public.delivery_daily_aggregate where interface_id = any(:ids)")
                .param("ids", interfaces.toArray(UUID[]::new)).query(Long.class).single());
        assertThat(visible).isZero();
    }

    /** 3 interfaces x 4 targets x 2 days, one delivery each, the outcome rotating over five shapes. */
    private void seed() {
        int n = 0;
        for (LocalDate day : List.of(YESTERDAY, TODAY)) {
            Instant at = day.atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(1));
            for (UUID iface : interfaces) {
                for (UUID target : targets) {
                    UUID id = UUID.randomUUID();
                    tx.inTenantTx(TENANT_A, () -> repository(TENANT_A).create(id, iface, "agg-" + id, null, target,
                            at));
                    switch (n++ % 5) {
                        case 0 -> {
                            attempts(id, at, 1);
                            settle(id, at, null);
                            deliveredOnce.add(id);
                        }
                        case 1 -> {
                            attempts(id, at, 3);
                            settle(id, at, null);
                        }
                        case 2 -> {
                            attempts(id, at, 2);
                            settle(id, at, OutcomeClass.PERMANENT);
                        }
                        case 3 -> {
                            attempts(id, at, 1);
                            settle(id, at, OutcomeClass.TRANSIENT);
                        }
                        default -> attempts(id, at, 2); // still stuck
                    }
                }
            }
        }
    }

    private void attempts(UUID id, Instant at, int count) {
        for (int i = 0; i < count; i++) {
            tx.inTenantTx(TENANT_A, () -> repository(TENANT_A).recordAttempt(id, 1, OutcomeClass.TRANSIENT, at, at,
                    "seed"));
        }
    }

    private void settle(UUID id, Instant at, OutcomeClass deadLetter) {
        UUID token = UUID.randomUUID();
        Instant later = at.plus(1, ChronoUnit.MINUTES);
        tx.inTenantTx(TENANT_A, () -> repository(TENANT_A).claimSend(id, token, later));
        tx.inTenantTx(TENANT_A, () -> deadLetter == null
                ? repository(TENANT_A).markDelivered(id, token, later)
                : repository(TENANT_A).deadLetter(id, token, deadLetter, later));
    }

    private List<DailyCounts> mine(List<DailyCounts> all) {
        Set<UUID> own = Set.copyOf(interfaces);
        return all.stream().filter(c -> own.contains(c.interfaceId()))
                .sorted(Comparator.comparing(DailyCounts::interfaceId).thenComparing(DailyCounts::targetId))
                .toList();
    }

    private JdbcDeliveryDailyAggregateStore store(UUID tenant) {
        return new JdbcDeliveryDailyAggregateStore(jdbc, DeliveryTestDatabase.tenant(tenant));
    }

    private JdbcDeliveryRepository repository(UUID tenant) {
        return new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(tenant));
    }
}
