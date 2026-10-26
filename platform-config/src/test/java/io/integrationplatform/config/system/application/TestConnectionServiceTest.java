package io.integrationplatform.config.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.destination.MqConnectionCheck;
import io.integrationplatform.config.destination.OutboundHttpCheck;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** T-126: an MQ system's test-connection is answered by the MQ port, audited, never NOT_AVAILABLE_YET. */
class TestConnectionServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String ID = UUID.randomUUID().toString();
    private static final String SETTINGS = "{\"host\":\"broker.example\",\"port\":61616,\"queue\":\"q\"}";

    private final JdbcSystemRepository systems = mock(JdbcSystemRepository.class);
    private final OutboundHttpCheck http = mock(OutboundHttpCheck.class);
    private final TestConnectionAudit audit = mock(TestConnectionAudit.class);

    TestConnectionServiceTest() {
        when(systems.findSystem(ID)).thenReturn(Optional.of(new SystemDetail(ID, "C", "N", "TARGET", "MQ", SETTINGS)));
    }

    @Test
    void mqSystemIsAnsweredByThePortAndAudited() {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("systemId", ID);
        port.put("type", "MQ");
        port.put("result", "FAILED");
        port.put("httpStatus", null);
        port.put("responseTimeMs", 3L);
        port.put("reason", "BROKER_UNREACHABLE");
        port.put("detail", null);
        port.put("message", "the broker did not answer");
        MqConnectionCheck mq = mock(MqConnectionCheck.class);
        when(mq.test(UUID.fromString(ID), SETTINGS)).thenReturn(port);

        Map<String, Object> answer = service(mq).test(ID, TENANT, ACTOR);

        assertThat(answer).isEqualTo(port);
        verify(audit).record(TENANT, ACTOR, ID, "MQ", "FAILED", "BROKER_UNREACHABLE", null);
        verifyNoInteractions(http);
    }

    @Test
    void withoutAnMqPortTheAnswerStaysNotAvailableYet() {
        Map<String, Object> answer = service((MqConnectionCheck) null).test(ID, TENANT, ACTOR);

        assertThat(answer.get("result")).isEqualTo("NOT_AVAILABLE_YET");
        verify(audit).record(TENANT, ACTOR, ID, "MQ", "NOT_AVAILABLE_YET", null, null);
    }

    @Test
    void aNonMqTypeNeverTouchesTheCheckRegisteredForMq() {
        String dbId = UUID.randomUUID().toString();
        when(systems.findSystem(dbId)).thenReturn(Optional.of(new SystemDetail(dbId, "C", "N", "TARGET", "DB", SETTINGS)));
        MqConnectionCheck mq = mock(MqConnectionCheck.class);

        Map<String, Object> answer = service(Map.of("MQ", mq)).test(dbId, TENANT, ACTOR);

        assertThat(answer.get("result")).isEqualTo("NOT_AVAILABLE_YET");
        verify(audit).record(TENANT, ACTOR, dbId, "DB", "NOT_AVAILABLE_YET", null, null);
        verifyNoInteractions(mq, http);
    }

    @Test
    void anMqTypeDispatchesToTheCheckRegisteredForItsType() {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("systemId", ID);
        port.put("type", "MQ");
        port.put("result", "OK");
        port.put("httpStatus", null);
        port.put("responseTimeMs", 7L);
        port.put("reason", null);
        port.put("detail", null);
        port.put("message", null);
        MqConnectionCheck mq = mock(MqConnectionCheck.class);
        when(mq.test(UUID.fromString(ID), SETTINGS)).thenReturn(port);

        Map<String, Object> answer = service(Map.of("MQ", mq)).test(ID, TENANT, ACTOR);

        assertThat(answer).isEqualTo(port);
        verify(audit).record(TENANT, ACTOR, ID, "MQ", "OK", null, null);
        verifyNoInteractions(http);
    }

    /** E16 lead integration: the type-keyed checks map sends a KAFKA system to its own check, never to MQ's. */
    @Test
    void aKafkaTypeDispatchesToTheCheckRegisteredForKafka() {
        String kafkaId = UUID.randomUUID().toString();
        String kafkaSettings = "{\"bootstrapServers\":\"broker.example:9092\",\"topic\":\"events\"}";
        when(systems.findSystem(kafkaId))
                .thenReturn(Optional.of(new SystemDetail(kafkaId, "C", "N", "TARGET", "KAFKA", kafkaSettings)));
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("systemId", kafkaId);
        port.put("type", "KAFKA");
        port.put("result", "OK");
        port.put("httpStatus", null);
        port.put("responseTimeMs", 4L);
        port.put("reason", null);
        port.put("detail", null);
        port.put("message", null);
        MqConnectionCheck kafka = mock(MqConnectionCheck.class);
        when(kafka.test(UUID.fromString(kafkaId), kafkaSettings)).thenReturn(port);
        MqConnectionCheck mq = mock(MqConnectionCheck.class);
        Map<String, MqConnectionCheck> registry = new LinkedHashMap<>();
        registry.put("MQ", mq);
        registry.put("KAFKA", kafka);

        Map<String, Object> answer = service(registry).test(kafkaId, TENANT, ACTOR);

        assertThat(answer).isEqualTo(port);
        verify(audit).record(TENANT, ACTOR, kafkaId, "KAFKA", "OK", null, null);
        verifyNoInteractions(mq, http);
    }

    private TestConnectionService service(MqConnectionCheck mq) {
        return new TestConnectionService(systems, TransactionOperations.withoutTransaction(), http, 10,
                Duration.ofMinutes(1), audit, mq);
    }

    private TestConnectionService service(Map<String, MqConnectionCheck> registry) {
        return new TestConnectionService(systems, TransactionOperations.withoutTransaction(), http, 10,
                Duration.ofMinutes(1), audit, registry);
    }
}
