package io.integrationplatform.delivery.e5;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E5-F1-S4 (T-185; ADR-007 Decision 7 rule 5; ADR-010 sections 5 and 6) - the two delivery moves of a target-credential
 * decryption failure, on the real partitioned tables as the restricted login:
 *
 * <ul>
 *   <li>unknown key-encryption-key id: {@code SENDING -> RETRY_WAIT} conditional on the claim token, a
 *       {@code KEY_UNAVAILABLE} delivery event, NO attempt row (no call left the process, no attempt is used up), the
 *       retry time on the bulkhead-full backoff (1 s doubling per consecutive deferral, 30 s cap, full jitter);</li>
 *   <li>authentication-tag mismatch: {@code SENDING -> DEAD_LETTERED} as PERMANENT, the event naming the reason
 *       "target credential unreadable".</li>
 * </ul>
 * A stale or foreign token moves nothing and records LATE_OUTCOME, like every other token-conditional move.
 */
@Tag("container")
class KeyFaultMovesContainerTest {

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private DeliveryTestDatabase.DeliveryTx tx;

    @BeforeEach
    void setUp() {
        pool = db.appPool();
        jdbc = JdbcClient.create(pool);
        tx = DeliveryTestDatabase.tx(pool);
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    private JdbcDeliveryRepository repository() {
        return new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A));
    }

    private JdbcDeliveryRetryStore store() {
        return new JdbcDeliveryRetryStore(jdbc, DeliveryTestDatabase.tenant(DeliveryTestDatabase.TENANT_A));
    }

    /** A delivery in SENDING under {@code token}, created now (the partitions span CURRENT_DATE-1..+7). */
    private UUID sending(UUID token) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
            repository().create(id, DeliveryTestDatabase.INTERFACE_ID, "key-" + id, 1L,
                    DeliveryTestDatabase.TARGET_ID, now);
            assertThat(repository().claimSend(id, token, now)).isTrue();
        });
        return id;
    }

    private List<String> events(UUID id) {
        return tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc.sql(
                        "select type || ':' || coalesce(detail, '-') from public.delivery_event"
                                + " where delivery_id = :id::uuid order by id")
                .params(Map.of("id", id.toString())).query(String.class).list());
    }

    @Test
    @DisplayName("Unknown key id: SENDING -> RETRY_WAIT, a KEY_UNAVAILABLE event, no attempt row, retry within the 1 s first step")
    void keyUnavailableWaitsWithoutAnAttempt() {
        UUID token = UUID.randomUUID();
        UUID id = sending(token);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        boolean moved = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().deferKeyUnavailable(id, token, now, 1.0));

        assertThat(moved).isTrue();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().state(id)))
                .contains(DeliveryState.RETRY_WAIT);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().countAttempts(id)))
                .as("no call left the process: no attempt row, no attempt used up").isZero();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().nextAttemptAt(id))).get()
                .satisfies(next -> assertThat(Duration.between(now, next)).isBetween(Duration.ZERO,
                        Duration.ofSeconds(1)));
        assertThat(events(id)).last().asString().startsWith("KEY_UNAVAILABLE");
    }

    @Test
    @DisplayName("Consecutive key deferrals back off 1 s, 2 s, 4 s ... capped at 30 s (bulkhead-full backoff, full jitter)")
    void consecutiveKeyDeferralsBackOff() {
        UUID token = UUID.randomUUID();
        UUID id = sending(token);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        List<Duration> waits = new java.util.ArrayList<>();
        for (int deferral = 1; deferral <= 7; deferral++) {
            UUID claim = token;
            tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                    () -> assertThat(store().deferKeyUnavailable(id, claim, now, 1.0)).isTrue());
            waits.add(Duration.between(now, tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                    () -> repository().nextAttemptAt(id)).orElseThrow()));
            UUID next = UUID.randomUUID();
            tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> {
                assertThat(repository().redrive(id, now)).isTrue();
                assertThat(repository().claimSend(id, next, now)).isTrue();
            });
            token = next;
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30));
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().countAttempts(id))).isZero();
    }

    @Test
    @Tag("negative")
    @DisplayName("Tag mismatch: SENDING -> DEAD_LETTERED as PERMANENT with the reason 'target credential unreadable'")
    void tagMismatchDeadLetters() {
        UUID token = UUID.randomUUID();
        UUID id = sending(token);

        boolean moved = tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().deadLetter(id, token, OutcomeClass.PERMANENT, "target credential unreadable"));

        assertThat(moved).isTrue();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().state(id)))
                .contains(DeliveryState.DEAD_LETTERED);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc.sql(
                        "select dead_letter_class from public.delivery where id = :id::uuid")
                .params(Map.of("id", id.toString())).query(String.class).single())).isEqualTo("PERMANENT");
        assertThat(events(id)).last().asString()
                .isEqualTo("DEAD_LETTER:PERMANENT: target credential unreadable");
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().countAttempts(id))).isZero();
    }

    @Test
    @Tag("negative")
    @DisplayName("A stale or foreign claim token moves nothing and records LATE_OUTCOME")
    void aStaleTokenMovesNothing() {
        UUID id = sending(UUID.randomUUID());
        UUID foreign = UUID.randomUUID();

        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().deferKeyUnavailable(id, foreign, Instant.now(), 0.5))).isFalse();
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> store().deadLetter(id, foreign, OutcomeClass.PERMANENT, "target credential unreadable")))
                .isFalse();

        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> repository().state(id)))
                .contains(DeliveryState.SENDING);
        assertThat(events(id)).filteredOn(event -> event.startsWith("LATE_OUTCOME")).hasSize(2);
    }
}
