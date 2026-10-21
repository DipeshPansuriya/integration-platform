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
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * T-149 wiring + controls, no containers: the stored settings and the delivery's mapped rows become the
 * parameterised insert through the five controls - an unapproved table is refused PERMANENT with nothing written,
 * a target that never answers is TRANSIENT (RETRY_WAIT at the route), a channel that dropped after the statement
 * may have been sent is AMBIGUOUS (the retry re-inserts the delivery id), and the restricted account's password
 * never outlives the attempt.
 */
@SuppressWarnings("unchecked")
class DbTargetControlsTest {

    static final String STORED_SETTINGS = """
            {"host":"broker.example","port":61616,"database":"orders","user":"restricted",
             "table":"orders_in","approvedTables":["orders_in"]}
            """;

    final UUID endpointId = UUID.randomUUID();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    final Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, endpointId, false);
    final DbSender sender = mock(DbSender.class);

    DbTargets targets(String settingsJson, char[] password) {
        DbSettings settings = DbSettings.fromJson(settingsJson);
        return id -> Optional.of(new DbTargets.DbTarget(settings, password));
    }

    DestinationGuard guard(String host, int port) {
        try {
            return new DestinationGuard(AllowList.of(List.of(host)),
                    name -> List.of(InetAddress.getLoopbackAddress()), List.of(host), List.of(port));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** An allow-list that approves nothing: every destination is refused before any data is sent. */
    DestinationGuard closedGuard() {
        return new DestinationGuard(AllowList.of(List.of()), name -> List.of(), List.of(), List.of());
    }

    DbTargetCall call(DbTargets targets, DbSender sender) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("awb", "ABX1");
        row.put("pieces", 2);
        DbOutbound outbound = new DbTargetOutbound(targets, s -> Optional.of(List.of(row)));
        return new DbTargetCall(outbound, sender, guard("broker.example", 61616), List.of(61616),
                breakers, meters, ObservationRegistry.create());
    }

    @Test
    void rowsLandInAnApprovedTableThroughTheParameterisedInsert() throws Exception {
        AtomicReference<DbMessage> inserted = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(onCall -> {
            inserted.set(onCall.getArgument(0));
            return null;
        }).when(sender).insert(any(DbMessage.class), any(UUID.class), any(InetAddress.class));

        call(targets(STORED_SETTINGS, "db-pass".toCharArray()), sender).call(send);

        assertThat(inserted.get().table()).isEqualTo("orders_in");
        assertThat(inserted.get().columns()).containsExactly("awb", "pieces");
        assertThat(inserted.get().values()).containsExactly(List.of("ABX1", 2));
        assertThat(inserted.get().settings().host()).isEqualTo("broker.example");
        assertThat(meters.get("db.target.outcome").tag("target_type", "DB").tag("outcome", "SUCCESS").counter())
                .isNotNull();
    }

    @Test
    void anUnlistedTableIsRefusedPermanentlyAndNothingIsWritten() throws Exception {
        String unlisted = "{\"host\":\"broker.example\",\"port\":61616,\"database\":\"orders\","
                + "\"user\":\"restricted\",\"table\":\"users\",\"approvedTables\":[\"orders_in\"]}";

        assertThatThrownBy(() -> call(targets(unlisted, "db-pass".toCharArray()), sender).call(send))
                .isInstanceOf(TargetFaultException.class)
                .extracting(f -> ((TargetFaultException) f).outcomeClass())
                .isEqualTo(OutcomeClass.PERMANENT);

        verify(sender, never()).insert(any(DbMessage.class), any(UUID.class), any(InetAddress.class));
        assertThat(meters.get("db.target.outcome").tag("outcome", "PERMANENT").counter()).isNotNull();
    }

    @Test
    void theTargetDatabaseDownIsTransient() throws Exception {
        DbSender down = mock(DbSender.class);
        org.mockito.Mockito.doThrow(new SQLException("connect refused", "08001"))
                .when(down).insert(any(DbMessage.class), any(UUID.class), any(InetAddress.class));

        assertThatThrownBy(() -> call(targets(STORED_SETTINGS, "db-pass".toCharArray()), down).call(send))
                .isInstanceOf(TargetFaultException.TransientFaultException.class);
    }

    @Test
    void aChannelDropAfterTheStatementMayHaveBeenSentIsAmbiguous() throws Exception {
        DbSender dropped = mock(DbSender.class);
        org.mockito.Mockito.doThrow(new SQLException("connection closed", "08006"))
                .when(dropped).insert(any(DbMessage.class), any(UUID.class), any(InetAddress.class));

        assertThatThrownBy(() -> call(targets(STORED_SETTINGS, "db-pass".toCharArray()), dropped).call(send))
                .isInstanceOf(TargetFaultException.class)
                .extracting(f -> ((TargetFaultException) f).outcomeClass())
                .isEqualTo(OutcomeClass.AMBIGUOUS);
    }

    @Test
    void theSendZeroesThePasswordAfterTheAttempt() throws Exception {
        char[] password = "db-secret".toCharArray();

        call(targets(STORED_SETTINGS, password), sender).call(send);

        assertThat(password).containsOnly('\0');
    }

    @Test
    void aRefusedHostIsPermanentAndWritesNothing() throws Exception {
        DestinationGuard closedGuard = closedGuard(); // an empty allow-list refuses every host

        DbOutbound outbound = new DbTargetOutbound(targets(STORED_SETTINGS, "db-pass".toCharArray()), s -> Optional.of(List.of(Map.of("awb", "1"))));
        DbTargetCall call = new DbTargetCall(outbound, sender, closedGuard, java.util.List.of(61616), breakers, meters,
                ObservationRegistry.create());

        assertThatThrownBy(() -> call.call(send))
                .isInstanceOf(TargetFaultException.class)
                .extracting(f -> ((TargetFaultException) f).outcomeClass())
                .isEqualTo(OutcomeClass.PERMANENT);
        verify(sender, never()).insert(any(DbMessage.class), any(UUID.class), any(InetAddress.class));
    }

    @Test
    void noMappedRowsRefuseTheSendAsPermanent() throws Exception {
        DbOutbound outbound = new DbTargetOutbound(targets(STORED_SETTINGS, "db-pass".toCharArray()),
                s -> Optional.empty());
        DbTargetCall call = new DbTargetCall(outbound, sender, guard("broker.example", 61616), List.of(61616), breakers, meters,
                ObservationRegistry.create());

        assertThatThrownBy(() -> call.call(send))
                .isInstanceOf(TargetFaultException.class)
                .extracting(f -> ((TargetFaultException) f).outcomeClass())
                .isEqualTo(OutcomeClass.PERMANENT);
        verify(sender, never()).insert(any(DbMessage.class), any(UUID.class), any(InetAddress.class));
    }

    @Test
    void aBrokenSettingsRowRefusesTheSendAsPermanent() throws Exception {
        DbTargets broken = id -> { throw new IllegalStateException("bad json"); };
        DbOutbound outbound = new DbTargetOutbound(broken, s -> Optional.of(List.of(Map.of("awb", "1"))));
        DbTargetCall call = new DbTargetCall(outbound, sender, guard("broker.example", 61616), List.of(61616), breakers, meters,
                ObservationRegistry.create());

        assertThatThrownBy(() -> call.call(send))
                .isInstanceOfSatisfying(TargetFaultException.class, fault -> {
                    assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT);
                    assertThat(fault.getMessage()).contains("are unusable: bad json");
                });
        verify(sender, never()).insert(any(DbMessage.class), any(UUID.class), any(InetAddress.class));
    }

    @Test
    void theResolvedMessageCarriesTheDeliveryIdColumnConvention() {
        DbTargetOutbound outbound = new DbTargetOutbound(targets(STORED_SETTINGS, "db-pass".toCharArray()),
                s -> Optional.of(List.of(Map.of("awb", "ABX1"))));

        assertThat(outbound.resolve(send).orElseThrow().settings().port()).isEqualTo(61616);
        assertThat(JdbcDbSender.DELIVERY_ID_COLUMN).isEqualTo("delivery_id");
    }
}