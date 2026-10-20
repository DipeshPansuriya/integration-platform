package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * T-148: the KAFKA test-connection answer, no Docker - the same answer shape the checks map audits
 * (systemId, type, result, httpStatus, responseTimeMs, reason, detail, message).
 */
class KafkaTopicTestConnectionTest {

    static final UUID ID = UUID.randomUUID();

    static DestinationGuard loopbackGuard(int port) {
        return new DestinationGuard(AllowList.of(List.of("localhost")), host -> List.of(InetAddress.getByName("127.0.0.1")),
                List.of("localhost"), List.of(port));
    }

    static Function<UUID, Optional<KafkaTopicSettings>> registry(KafkaTopicSettings settings) {
        return id -> Optional.ofNullable(settings);
    }

    static KafkaTopicSettings settings(int port) {
        return KafkaTopicSettings.fromJson(
                "{\"bootstrapServers\":\"localhost:" + port + ",other:9093\",\"topic\":\"orders-out\"}");
    }

    @Test
    void reachableBrokerAnswersSuccessWithConnectionMessage() {
        Map<String, Object> answer = new KafkaTopicTestConnection(registry(settings(9092)), loopbackGuard(9092),
                (host, port, timeout) -> {
                }, Duration.ofSeconds(5)).test(ID);
        assertThat(answer.keySet()).containsExactly("systemId", "type", "result", "httpStatus", "responseTimeMs",
                "reason", "detail", "message");
        assertThat(answer).containsEntry("systemId", ID.toString()).containsEntry("type", "KAFKA")
                .containsEntry("result", "SUCCESS").containsEntry("httpStatus", null).containsEntry("reason", null)
                .containsEntry("detail", null).containsEntry("message", "the broker accepted a connection");
        assertThat(answer.get("responseTimeMs")).isInstanceOf(Long.class);
    }

    @Test
    void unreachableBrokerIsFailedNotAnException() {
        Map<String, Object> answer = new KafkaTopicTestConnection(registry(settings(9092)), loopbackGuard(9092),
                (host, port, timeout) -> {
                    throw new IOException("connection refused");
                }, Duration.ofSeconds(5)).test(ID);
        assertThat(answer).containsEntry("result", "FAILED").containsEntry("reason", "BROKER_UNREACHABLE")
                .containsEntry("message", "the broker did not answer");
    }

    @Test
    void refusedHostIsAnsweredBeforeAnyProbe() {
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("localhost")),
                host -> List.of(InetAddress.getByName("127.0.0.1")));
        boolean[] probed = new boolean[1];
        Map<String, Object> answer = new KafkaTopicTestConnection(
                registry(KafkaTopicSettings.fromJson("{\"bootstrapServers\":\"evil.example.com:9092\",\"topic\":\"t\"}")),
                guard, (host, port, timeout) -> probed[0] = true, Duration.ofSeconds(5)).test(ID);
        assertThat(answer).containsEntry("result", "FAILED").containsEntry("reason", "DESTINATION_REFUSED");
        assertThat(probed[0]).as("nothing is dialled after a refusal").isFalse();
    }

    @Test
    void unknownSystemIsNotFound() {
        Map<String, Object> answer = new KafkaTopicTestConnection(id -> Optional.empty(), loopbackGuard(9092),
                (host, port, timeout) -> {
                }, Duration.ofSeconds(5)).test(ID);
        assertThat(answer).containsEntry("result", "FAILED").containsEntry("reason", "NOT_FOUND");
    }

    @Test
    void badSettingsIsAnsweredNotThrown() {
        Map<String, Object> answer = new KafkaTopicTestConnection(
                id -> Optional.of(KafkaTopicSettings.fromJson("{\"bootstrapServers\":\"localhost:9092\"}")),
                loopbackGuard(9092), (host, port, timeout) -> {
                }, Duration.ofSeconds(5)).test(ID);
        assertThat(answer).containsEntry("result", "FAILED").containsEntry("reason", "BAD_SETTINGS");
    }
}
