package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * T-149: test-connection for a DB system from the registry (KafkaTopicTestConnection pattern) - the host passes
 * the destination guard before any probe, a malformed settings row is a FAILED/BAD_SETTINGS answer and a database
 * that does not answer is a FAILED answer, never an exception. The protocol-level connect with the restricted
 * account is proven on the container leg (the scenario); the probe answers reachability, audited by the caller.
 */
public class DbTestConnection {

    private final DestinationGuard guard;
    private final DbBrokerProbe probe;
    private final Duration timeout;

    public DbTestConnection(DestinationGuard guard, DbBrokerProbe probe, Duration timeout) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public Map<String, Object> test(java.util.UUID systemId, String settingsJson) {
        DbSettings settings;
        try {
            settings = DbSettings.fromJson(settingsJson);
        } catch (RuntimeException e) {
            return answer(systemId, "FAILED", "BAD_SETTINGS", 0, "the DB settings are unusable ("
                    + e.getClass().getSimpleName() + ")");
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
            return answer(systemId, "FAILED", "DATABASE_UNREACHABLE", elapsedMs(start),
                    "the target database did not answer");
        }
        return answer(systemId, "SUCCESS", null, elapsedMs(start), "the target database accepted a connection");
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static Map<String, Object> answer(java.util.UUID systemId, String result, String reason, long ms,
            String message) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("systemId", systemId.toString());
        answer.put("type", "DB");
        answer.put("result", result);
        answer.put("httpStatus", null);
        answer.put("responseTimeMs", ms);
        answer.put("reason", reason);
        answer.put("detail", null);
        answer.put("message", message);
        return answer;
    }
}