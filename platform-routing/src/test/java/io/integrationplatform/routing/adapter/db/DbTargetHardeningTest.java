package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E16 T-149 hardening, no containers: the statement is built from validated identifiers and parameters only, a
 * mapped column can never smuggle SQL or the platform's own idempotency column, and the send names a restricted
 * account - it never guesses one.
 */
class DbTargetHardeningTest {

    static final String SETTINGS = """
            {"host":"db.example","port":5432,"database":"orders","user":"restricted",
             "table":"orders_in","approvedTables":["orders_in"]}
            """;
    static final String SETTINGS_NO_USER = """
            {"host":"db.example","port":5432,"database":"orders",
             "table":"orders_in","approvedTables":["orders_in"]}
            """;

    final DbSender sender = mock(DbSender.class);
    final Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false);

    DbTargetCall call(String settingsJson, Map<String, ?> row) {
        DbSettings settings = DbSettings.fromJson(settingsJson);
        DbTargets targets = id -> Optional.of(new DbTargets.DbTarget(settings, "pw".toCharArray()));
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("db.example")),
                name -> List.of(InetAddress.getLoopbackAddress()), List.of("db.example"), List.of(5432));
        return new DbTargetCall(new DbTargetOutbound(targets, s -> Optional.of(List.of(row))), sender, guard, List.of(5432),
                CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), ObservationRegistry.create());
    }

    private void assertPermanentAndNothingSent(Runnable attempt) throws Exception {
        assertThatThrownBy(attempt::run).isInstanceOfSatisfying(TargetFaultException.class,
                fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        verify(sender, never()).insert(any(DbMessage.class), any(UUID.class), any(InetAddress.class));
    }

    @Test
    @Tag("negative")
    @Tag("security")
    void aMappedColumnNameCarryingSqlIsRefusedPermanentAndNothingIsSent() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("awb) VALUES (1); DROP TABLE orders_in; --", "ABX1");

        assertPermanentAndNothingSent(() -> call(SETTINGS, row).call(send));
    }

    @Test
    @Tag("negative")
    @Tag("security")
    void aMappedColumnCannotBeThePlatformsIdempotencyColumn() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("awb", "ABX1");
        row.put("Delivery_Id", UUID.randomUUID()); // would void the platform's own idempotency column

        assertPermanentAndNothingSent(() -> call(SETTINGS, row).call(send));
    }

    @Test
    @Tag("negative")
    @Tag("security")
    void aSendWithoutARestrictedAccountIsRefusedNotGuessed() throws Exception {
        assertPermanentAndNothingSent(() -> call(SETTINGS_NO_USER, Map.of("awb", "ABX1")).call(send));
    }

    @Test
    @Tag("security")
    void theStatementTextHoldsIdentifiersAndPlaceholdersOnly() {
        String hostile = "x'); DROP TABLE orders_in; --";
        DbMessage message = new DbMessage(DbSettings.fromJson(SETTINGS), "restricted", null, "orders_in",
                List.of("awb", "pieces"), List.<List<Object>>of(List.of(hostile, 2)));

        String statement = JdbcDbSender.insert(message);

        assertThat(statement).isEqualTo("INSERT INTO orders_in (awb, pieces, delivery_id) VALUES (?, ?, ?)");
        assertThat(statement).doesNotContain("DROP").doesNotContain(hostile);
    }
}
