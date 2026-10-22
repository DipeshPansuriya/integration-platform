package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T-149: test-connection for a DB system from the registry (KafkaTopicTestConnection pattern) - the host passes
 * the destination guard before any probe, a malformed settings row is a FAILED/BAD_SETTINGS answer and a database
 * that does not answer is a FAILED answer, never an exception. The protocol-level connect with the restricted
 * account is proven on the container leg (the scenario); the probe answers reachability, audited by the caller.
 *
 * <p>R6 (CWE-918, limited): the SAME port allow-list the send path applies ({@code DbTargetCall.requireAllowedPort},
 * {@code platform.config.destinations.db-allowed-ports}) is applied here before anything is probed - an off-list
 * port is a {@code DESTINATION_REFUSED} answer, so an allow-listed host cannot be port-scanned with SUCCESS /
 * DATABASE_UNREACHABLE answers over ports 1-65535.
 *
 * <p>E16 F3 (design note task 11, Karan change 4): with a privilege probe wired the answer is the account's truth -
 * after the socket probe the same guard-pinned address is dialled credentialed over JDBC on an allowed port and the
 * account's grants on the exact target table are reported in the response's {@code detail}. A missing credential or
 * account is its own FAILED reason; a refusal is never read as success.
 */
public class DbTestConnection {

    private final DestinationGuard guard;
    private final DbBrokerProbe probe;
    /** Null when no credential slice is composed - then the answer is the socket-only reachability it always was. */
    private final DbPrivilegeProbe privileges;
    /** R6: the send path's allow-list, enforced before any probe; NULL only for the legacy socket-only constructor,
     * which keeps the pre-R6 answer shape it exists for (the production bean always hands the operator's list). */
    private final java.util.Set<Integer> allowedPorts;
    private final Duration timeout;

    public DbTestConnection(DestinationGuard guard, DbBrokerProbe probe, Duration timeout) {
        this(guard, probe, null, (java.util.Collection<Integer>) null, timeout);
    }

    public DbTestConnection(DestinationGuard guard, DbBrokerProbe probe, java.util.Set<Integer> allowedPorts,
            Duration timeout) {
        this(guard, probe, null, (java.util.Collection<Integer>) allowedPorts, timeout);
    }

    public DbTestConnection(DestinationGuard guard, DbBrokerProbe probe, DbPrivilegeProbe privileges,
            java.util.Collection<Integer> allowedPorts, Duration timeout) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.probe = Objects.requireNonNull(probe, "probe");
        if (privileges != null) {
            Objects.requireNonNull(allowedPorts, "the credentialed probe runs only on an operator port list");
        }
        this.privileges = privileges;
        this.allowedPorts = allowedPorts == null ? null : java.util.Set.copyOf(allowedPorts);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public Map<String, Object> test(java.util.UUID systemId, String settingsJson) {
        DbSettings settings;
        try {
            settings = DbSettings.fromJson(settingsJson);
        } catch (RuntimeException e) {
            return answer(systemId, "FAILED", "BAD_SETTINGS", 0, "the DB settings are unusable ("
                    + e.getClass().getSimpleName() + ")", null);
        }
        try {
            // F1: the port first (nothing spent on a refused port), exactly like the send path (R6; the legacy
            // constructor carries no list and keeps the pre-R6 behaviour)
            if (allowedPorts != null) {
                DbTargetCall.requireAllowedPort(settings.port(), allowedPorts);
            }
            guard.checkHost(settings.host());
        } catch (DestinationRefusedException | IllegalStateException e) {
            return answer(systemId, "FAILED", "DESTINATION_REFUSED", 0, String.valueOf(e.getMessage()), null);
        }
        long start = System.nanoTime();
        try {
            probe.probe(settings.host(), settings.port(), timeout);
        } catch (IOException e) {
            return answer(systemId, "FAILED", "DATABASE_UNREACHABLE", elapsedMs(start),
                    "the target database did not answer", null);
        }
        if (privileges == null) {
            return answer(systemId, "SUCCESS", null, elapsedMs(start), "the target database accepted a connection",
                    null);
        }
        try {
            // F1 (CWE-918), the send path's order: the port first (no DNS spent on a refused port), then the FULL
            // guard - the address the JDBC driver dials is the checked, pinned one
            DbTargetCall.requireAllowedPort(settings.port(), allowedPorts);
            io.integrationplatform.config.destination.CheckedDestination checked =
                    guard.check(java.net.URI.create("http://" + settings.host() + ":" + settings.port()));
            List<String> granted = privileges.probe(systemId, settings, checked.pinnedAddress(), timeout);
            return answer(systemId, "SUCCESS", null, elapsedMs(start),
                    "the target database accepted a credentialed connection", detail(settings, granted));
        } catch (DestinationRefusedException | IllegalStateException e) {
            return answer(systemId, "FAILED", "DESTINATION_REFUSED", elapsedMs(start), String.valueOf(e.getMessage()),
                    null);
        } catch (DbPrivilegeProbe.ProbeRefused e) {
            return answer(systemId, "FAILED", e.reason, elapsedMs(start), String.valueOf(e.getMessage()), null);
        } catch (IOException e) {
            return answer(systemId, "FAILED", "DATABASE_UNREACHABLE", elapsedMs(start),
                    "the target database did not answer", null);
        } catch (RuntimeException e) {
            return answer(systemId, "FAILED", "PROBE_FAILED", elapsedMs(start), "the privilege probe could not run ("
                    + e.getClass().getSimpleName() + ")", null);
        }
    }

    /** Karan change 4: the grants in the response - named on the table they apply to; no table, nothing reported. */
    private static String detail(DbSettings settings, List<String> granted) {
        if (settings.table() == null) {
            return null;
        }
        List<String> extra = granted.stream().filter(p -> !p.equals("INSERT") && !p.equals("SELECT")).toList();
        return "the account '" + settings.user() + "' holds on '" + settings.table() + "': "
                + (granted.isEmpty() ? "none" : String.join(", ", granted))
                + (extra.isEmpty() ? "" : " - WIDE ACCOUNT: more than INSERT and SELECT (" + String.join(", ", extra)
                + "); the send needs only those two");
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static Map<String, Object> answer(java.util.UUID systemId, String result, String reason, long ms,
            String message, String detail) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("systemId", systemId.toString());
        answer.put("type", "DB");
        answer.put("result", result);
        answer.put("httpStatus", null);
        answer.put("responseTimeMs", ms);
        answer.put("reason", reason);
        answer.put("detail", detail);
        answer.put("message", message);
        return answer;
    }
}