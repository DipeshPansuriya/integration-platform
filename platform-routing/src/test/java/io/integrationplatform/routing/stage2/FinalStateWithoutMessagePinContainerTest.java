package io.integrationplatform.routing.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.domain.delivery.OutcomeClass;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Row 278 T80 (E5 delivery NPE, amit-developer lane A): a delivery row with NO stage-1 message pin
 * ({@code message_id} null - the nullable ADD-only column of V2_3_930:15, never backfilled, and every row written
 * by the E2 {@code create} insert) must still reach a final state once the stage-1 {@code public.message} table
 * exists (V2_1_810, migrated here with every module's migrations). Before the fix the final-state lock order
 * passed the null pin to {@code lockMessageRow}, which only skipped it while the message table was absent
 * (wave 0) - with the table present it threw {@code NullPointerException} on {@code messageId.toString()}, the
 * exact failure of {@code E5KeyFaultContainerTest} on the unreadable-credential dead-letter path
 * ({@code CredentialedDelivery.unreadable -> DeliveryRetryStore.deadLetter}).
 */
@Tag("container")
class FinalStateWithoutMessagePinContainerTest {

    private static final UUID TENANT = RoutingTestDatabase.TENANT_A;

    private static HikariDataSource pool;
    private static JdbcClient jdbc;
    private static RoutingTestDatabase.TenantTx tx;

    @BeforeAll
    static void database() {
        pool = RoutingTestDatabase.get().appPool(2);
        jdbc = JdbcClient.create(pool);
        tx = new RoutingTestDatabase.TenantTx(pool);
    }

    @AfterAll
    static void closePool() {
        pool.close();
    }

    @Test
    void anUnpinnedDeliveryDeadLettersWithTheNamedReasonWhenTheMessageTableExists() {
        UUID delivery = id("t80-unpinned-delivery");
        UUID claimToken = id("t80-unpinned-claim");
        Instant now = Instant.now();
        tx.inTenantTx(TENANT, () -> {
            repository().create(delivery, RoutingTestDatabase.INTERFACE_ID, "t80-unpinned", null,
                    id("t80-endpoint"), now);
            assertThat(repository().claimSend(delivery, claimToken, now)).isTrue();
        });

        boolean messageTablePresent = stampedRead(jc -> jc.sql("select to_regclass('public.message') is not null")
                .query(Boolean.class).single());
        assertThat(messageTablePresent).as("precondition: the stage-1 message table exists (not wave 0)").isTrue();
        UUID pin = stampedRead(jc -> jc.sql("select message_id from public.delivery where id = :id::uuid")
                .params(Map.of("id", delivery.toString())).query(UUID.class).optional().orElse(null));
        assertThat(pin).as("precondition: the delivery carries no stage-1 message pin").isNull();

        // the dead-letter call CredentialedDelivery.unreadable makes, through the real retry store
        boolean[] moved = new boolean[1];
        tx.inTenantTx(TENANT, () -> moved[0] = new JdbcDeliveryRetryStore(jdbc, RoutingTestDatabase.tenant(TENANT))
                .deadLetter(delivery, claimToken, OutcomeClass.PERMANENT, "target credential unreadable"));

        assertThat(moved[0]).as("the claim-conditional dead-letter matched the SENDING row").isTrue();
        String state = stampedRead(jc -> jc.sql("select state || ':' || dead_letter_class from public.delivery"
                        + " where id = :id::uuid")
                .params(Map.of("id", delivery.toString())).query(String.class).single());
        assertThat(state).isEqualTo("DEAD_LETTERED:PERMANENT");
    }

    private static JdbcDeliveryRepository repository() {
        return new JdbcDeliveryRepository(jdbc, RoutingTestDatabase.tenant(TENANT));
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads run inside a tenant-stamped transaction (m-70b8a1: delivery RLS fails closed on an unstamped read). */
    private static <T> T stampedRead(java.util.function.Function<JdbcClient, T> query) {
        return tx.stamping(RoutingTestDatabase.tenant(TENANT)).execute(status -> query.apply(tx.jdbcClient()));
    }
}
