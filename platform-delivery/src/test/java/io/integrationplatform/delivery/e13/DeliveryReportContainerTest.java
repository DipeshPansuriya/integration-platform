package io.integrationplatform.delivery.e13;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryDailyAggregateStore;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.port.DeliveryDailyAggregates.DailyCounts;
import io.integrationplatform.delivery.report.DeliveryReportController;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * T-139 (E13-C3/C4): the tenant delivery report (GET /api/v1/reports/delivery?day=) reads the daily aggregate and
 * equals the raw delivery + delivery_attempt rows (successes, failures by reason, retries made, retried-then-succeeded,
 * still stuck) with a difference of 0, and is tenant-scoped under FORCE RLS.
 * E14 T-143 brings the scheduled refresh; E13 drives refresh(day) directly in each test.
 */
@Tag("container")
class DeliveryReportContainerTest {

    private static final UUID TENANT_A = DeliveryTestDatabase.TENANT_A;
    private static final UUID TENANT_B = DeliveryTestDatabase.TENANT_B;
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private final UUID iface = UUID.randomUUID();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private DeliveryTestDatabase.DeliveryTx tx;
    private UUID deliveredOnce;

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
    @DisplayName("Seeded equality")
    void seededEquality() {
        seed();
        refresh(TENANT_A); // E14 T-143 schedules this refresh; E13 calls it directly

        List<DailyCounts> report = mine(controller(TENANT_A).report(TODAY).getBody());

        assertEqualsRaw(report);
        assertThat(sum(report, DailyCounts::successes)).isEqualTo(2);
        assertThat(sum(report, DailyCounts::failuresTransient)).isEqualTo(1);
        assertThat(sum(report, DailyCounts::failuresAmbiguous)).isEqualTo(1);
        assertThat(sum(report, DailyCounts::failuresPermanent)).isEqualTo(1);
        assertThat(sum(report, DailyCounts::retriedThenSucceeded)).isEqualTo(1);
        assertThat(sum(report, DailyCounts::stillStuck)).isEqualTo(1);
        assertThat(sum(report, DailyCounts::retriesMade)).isPositive();
    }

    @Test
    @DisplayName("A difference fails the test")
    void aDifferenceFailsTheTest() {
        seed();
        refresh(TENANT_A); // direct refresh until E14 T-143
        Instant now = Instant.now();
        tx.inTenantTx(TENANT_A, () -> repository(TENANT_A)
                .recordAttempt(deliveredOnce, 1, OutcomeClass.TRANSIENT, now, now, "late"));

        List<DailyCounts> stale = mine(controller(TENANT_A).report(TODAY).getBody());

        assertThatThrownBy(() -> assertEqualsRaw(stale)).isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("The report is tenant-scoped")
    void theReportIsTenantScoped() {
        seed();
        refresh(TENANT_A); // direct refresh until E14 T-143
        refresh(TENANT_B);

        assertThat(mine(controller(TENANT_A).report(TODAY).getBody())).hasSize(6);
        assertThat(mine(controller(TENANT_B).report(TODAY).getBody())).isEmpty();
        TenantContext none = Optional::empty;
        DeliveryReportController unbound = new DeliveryReportController(
                new JdbcDeliveryDailyAggregateStore(jdbc, none), new DataSourceTransactionManager(pool));
        assertThatThrownBy(() -> unbound.report(TODAY)).isInstanceOf(RuntimeException.class);
    }

    private void assertEqualsRaw(List<DailyCounts> report) {
        List<DailyCounts> raw = mine(tx.inTenantTx(TENANT_A, () -> store(TENANT_A).direct(TODAY)));
        assertThat(report).hasSize(6).containsExactlyElementsOf(raw);
    }

    /** One interface x 6 targets, one delivery each: every outcome shape the report counts. */
    private void seed() {
        Instant at = TODAY.atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(1));
        for (int shape = 0; shape < 6; shape++) {
            UUID id = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            tx.inTenantTx(TENANT_A, () -> repository(TENANT_A).create(id, iface, "rep-" + id, null, target, at));
            switch (shape) {
                case 0 -> { attempts(id, at, 1); settle(id, at, null); deliveredOnce = id; }
                case 1 -> { attempts(id, at, 3); settle(id, at, null); }
                case 2 -> { attempts(id, at, 2); settle(id, at, OutcomeClass.PERMANENT); }
                case 3 -> { attempts(id, at, 1); settle(id, at, OutcomeClass.TRANSIENT); }
                case 4 -> { attempts(id, at, 1); settle(id, at, OutcomeClass.AMBIGUOUS); }
                default -> attempts(id, at, 2); // still stuck
            }
        }
    }

    private void attempts(UUID id, Instant at, int count) {
        for (int i = 0; i < count; i++) {
            tx.inTenantTx(TENANT_A, () -> repository(TENANT_A)
                    .recordAttempt(id, 1, OutcomeClass.TRANSIENT, at, at, "seed"));
        }
    }

    private void settle(UUID id, Instant at, OutcomeClass deadLetter) {
        UUID token = UUID.randomUUID();
        Instant later = at.plus(1, ChronoUnit.MINUTES);
        tx.inTenantTx(TENANT_A, () -> repository(TENANT_A).claimSend(id, token, later));
        tx.inTenantTx(TENANT_A, () -> {
            if (deadLetter == null) {
                repository(TENANT_A).markDelivered(id, token, later);
            } else {
                repository(TENANT_A).deadLetter(id, token, deadLetter, later);
            }
        });
    }

    private void refresh(UUID tenant) {
        tx.inTenantTx(tenant, () -> store(tenant).refresh(TODAY));
    }

    private DeliveryReportController controller(UUID tenant) {
        return new DeliveryReportController(store(tenant), new DataSourceTransactionManager(pool));
    }

    private List<DailyCounts> mine(List<DailyCounts> all) {
        return all.stream().filter(c -> c.interfaceId().equals(iface))
                .sorted(Comparator.comparing(DailyCounts::targetId)).toList();
    }

    private static long sum(List<DailyCounts> rows, ToLongFunction<DailyCounts> f) {
        return rows.stream().mapToLong(f).sum();
    }

    private JdbcDeliveryDailyAggregateStore store(UUID tenant) {
        return new JdbcDeliveryDailyAggregateStore(jdbc, DeliveryTestDatabase.tenant(tenant));
    }

    private JdbcDeliveryRepository repository(UUID tenant) {
        return new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(tenant));
    }
}
