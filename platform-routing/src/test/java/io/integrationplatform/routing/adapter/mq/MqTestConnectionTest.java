package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** E10-F1-S2 / T-126: the MQ test-connection answers from the registry's MQ settings, in the API answer shape. */
class MqTestConnectionTest {

    private static final UUID SYSTEM = UUID.randomUUID();
    private final List<String> probed = new ArrayList<>();

    private MqTestConnection connection(Optional<MqSettings> registry, BrokerProbe probe) {
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("mq.partner.example")), host -> {
            throw new UnknownHostException("save-time check never resolves: " + host);
        });
        return new MqTestConnection(id -> id.equals(SYSTEM) ? registry : Optional.empty(), guard, probe,
                Duration.ofSeconds(2));
    }

    @Test
    void aReachableBrokerFromTheRegistryReportsSuccess() {
        Map<String, Object> answer = connection(Optional.of(new MqSettings("mq.partner.example", 61616, "ORDERS.IN")),
                (host, port, queue, timeout) -> probed.add(host + ":" + port + "/" + queue)).test(SYSTEM);

        assertThat(answer).containsEntry("result", "SUCCESS").containsEntry("type", "MQ")
                .containsEntry("systemId", SYSTEM.toString())
                .containsKeys("httpStatus", "responseTimeMs", "reason", "detail", "message");
        assertThat(probed).containsExactly("mq.partner.example:61616/ORDERS.IN");
    }

    @Test
    void anUnreachableBrokerIsAFailureNotAnException() {
        Map<String, Object> answer = connection(Optional.of(new MqSettings("mq.partner.example", 61616, "Q")),
                (host, port, queue, timeout) -> {
                    throw new IOException("connection refused");
                }).test(SYSTEM);

        assertThat(answer).containsEntry("result", "FAILED").containsEntry("reason", "BROKER_UNREACHABLE");
    }

    @Test
    void aHostOffTheAllowListIsRefusedBeforeAnyProbe() {
        Map<String, Object> answer = connection(Optional.of(new MqSettings("10.0.0.5", 61616, "Q")),
                (host, port, queue, timeout) -> probed.add(host)).test(SYSTEM);

        assertThat(answer).containsEntry("result", "FAILED").containsEntry("reason", "DESTINATION_REFUSED");
        assertThat(probed).isEmpty();
    }

    @Test
    void aSystemNotInThisTenantsRegistryIsNotFound() {
        Map<String, Object> answer = connection(Optional.empty(), (h, p, q, t) -> probed.add(h))
                .test(UUID.randomUUID());

        assertThat(answer).containsEntry("result", "FAILED").containsEntry("reason", "NOT_FOUND");
        assertThat(probed).isEmpty();
    }

    @Test
    void registrySettingsJsonIsValidated() {
        assertThat(MqSettings.fromJson("{\"host\":\"mq.partner.example\",\"port\":61616,\"queue\":\"Q\"}"))
                .isEqualTo(new MqSettings("mq.partner.example", 61616, "Q"));
        assertThatThrownBy(() -> MqSettings.fromJson("{\"host\":\"h\",\"port\":0,\"queue\":\"Q\"}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MqSettings.fromJson("{\"host\":\"h\",\"port\":1}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
