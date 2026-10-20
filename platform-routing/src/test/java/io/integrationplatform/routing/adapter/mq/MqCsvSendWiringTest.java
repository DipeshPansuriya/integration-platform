package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.integrationplatform.routing.adapter.mq.MqCsvTargets.MqCsvTarget;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T-125 wiring, no containers: the endpoint's stored registry JSON decides the queue bytes, and a row the
 * contract cannot express leaves MqTargetCall as PERMANENT before any connection is attempted.
 */
class MqCsvSendWiringTest {

    static final String STORED_CONTRACT = """
            {"columns":[{"name":"awb","type":"TEXT"},{"name":"pieces","type":"NUMBER"}],
             "delimiter":";","quote":"\\"","quoteAll":false,"encoding":"UTF-8",
             "lineEnding":"CRLF","header":false}
            """;

    final UUID endpointId = UUID.randomUUID();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    final Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, endpointId, false);

    MqCsvTarget target(char[] password) {
        return new MqCsvTarget(new MqSettings("localhost", 61616, "t125.csv"), "user", password,
                CsvContractSettings.fromJson(STORED_CONTRACT));
    }

    MqTargetCall call(MqCsvTargets targets, MqCsvRows rows) {
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("localhost")),
                host -> List.of(InetAddress.getByName("127.0.0.1")), List.of("localhost"), List.of(61616));
        return new MqTargetCall(new MqCsvOutbound(targets, rows), guard, breakers, meters,
                ObservationRegistry.create(), Duration.ofSeconds(2));
    }

    @Test
    void storedContractDrivesTheQueueBytes() {
        MqCsvOutbound outbound = new MqCsvOutbound(id -> Optional.of(target("pw".toCharArray())),
                s -> Optional.of(List.of(Map.of("awb", "ABX1", "pieces", 2))));

        byte[] expected = { 'A', 'B', 'X', '1', ';', '2', '\r', '\n' };
        assertThat(outbound.resolve(send).orElseThrow().body()).isEqualTo(expected);
    }

    @Test
    void contractFailureZeroesThePasswordTheSendNeverReaches() {
        char[] password = "secret".toCharArray();
        MqCsvOutbound outbound = new MqCsvOutbound(id -> Optional.of(target(password)),
                s -> Optional.of(List.of(Map.of("awb", "ABX1", "pieces", "three"))));

        assertThatThrownBy(() -> outbound.resolve(send)).isInstanceOf(CsvContractException.class);
        assertThat(password).containsOnly('\0');
    }

    @Test
    void textInNumericColumnLeavesTheCallAsPermanentAndNeverDials() {
        MqTargetCall call = call(id -> Optional.of(target("pw".toCharArray())),
                s -> Optional.of(List.of(Map.of("awb", "ABX1", "pieces", "three"))));

        assertThatThrownBy(() -> call.call(send))
                .isInstanceOf(TargetFaultException.class)
                .extracting(f -> ((TargetFaultException) f).outcomeClass())
                .isEqualTo(OutcomeClass.PERMANENT);
        assertThat(meters.get("mq.target.outcome").tag("outcome", "PERMANENT").counter()).isNotNull();
        assertThat(breakers.circuitBreaker("mq:" + endpointId, MqTargetCall.BREAKER).getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void endpointWithoutStoredContractRefusesTheSendAsPermanent() {
        MqTargetCall call = call(id -> Optional.empty(), s -> Optional.of(List.of(Map.of("awb", "1", "pieces", 1))));

        assertThatThrownBy(() -> call.call(send))
                .isInstanceOf(TargetFaultException.class)
                .extracting(f -> ((TargetFaultException) f).outcomeClass())
                .isEqualTo(OutcomeClass.PERMANENT);
    }

    @Test
    void missingTransformRowsRefuseTheSendAsPermanent() {
        MqTargetCall call = call(id -> Optional.of(target("pw".toCharArray())), s -> Optional.empty());

        assertThatThrownBy(() -> call.call(send))
                .isInstanceOf(TargetFaultException.class)
                .hasMessageContaining("no MQ message")
                .extracting(f -> ((TargetFaultException) f).outcomeClass())
                .isEqualTo(OutcomeClass.PERMANENT);
    }

    @Test
    void successPathKeepsThePasswordUsableUntilTheSendFinishes() {
        char[] password = "secret".toCharArray();
        MqMessage message = new MqCsvOutbound(id -> Optional.of(target(password)),
                s -> Optional.of(List.of(Map.of("awb", "ABX1", "pieces", 2)))).resolve(send).orElseThrow();

        assertThat(message.password()).isEqualTo("secret".toCharArray());
    }
}
