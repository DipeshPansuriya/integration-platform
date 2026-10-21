package io.integrationplatform.routing.adapter.kafkatopic;

import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * T-148: test-connection for a KAFKA system, answered from the registry - the host passes the save-time guard
 * before any probe, and a broker that does not answer is a FAILED answer, never an exception.
 */
public class KafkaTopicTestConnection {

    private final Function<UUID, Optional<KafkaTopicSettings>> registry;
    private final DestinationGuard guard;
    private final KafkaBrokerProbe probe;
    private final Duration timeout;

    public KafkaTopicTestConnection(Function<UUID, Optional<KafkaTopicSettings>> registry, DestinationGuard guard,
            KafkaBrokerProbe probe, Duration timeout) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public Map<String, Object> test(UUID systemId) {
        KafkaTopicSettings settings;
        try {
            settings = registry.apply(systemId).orElse(null);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return answer(systemId, "FAILED", "BAD_SETTINGS", 0, e.getMessage());
        }
        if (settings == null) {
            return answer(systemId, "FAILED", "NOT_FOUND", 0, "no KAFKA system with this id");
        }
        try {
            guard.checkHost(settings.host());
        } catch (DestinationRefusedException e) {
            return answer(systemId, "FAILED", "DESTINATION_REFUSED", 0, String.valueOf(e.getMessage()));
        }
        long start = System.nanoTime();
        try {
            probe.probe(settings.host(), settings.port(), timeout);
        } catch (IOException e) {
            return answer(systemId, "FAILED", "BROKER_UNREACHABLE", elapsedMs(start), "the broker did not answer");
        }
        return answer(systemId, "SUCCESS", null, elapsedMs(start), "the broker accepted a connection");
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static Map<String, Object> answer(UUID systemId, String result, String reason, long ms, String message) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("systemId", systemId.toString());
        answer.put("type", "KAFKA");
        answer.put("result", result);
        answer.put("httpStatus", null);
        answer.put("responseTimeMs", ms);
        answer.put("reason", reason);
        answer.put("detail", null);
        answer.put("message", message);
        return answer;
    }
}
