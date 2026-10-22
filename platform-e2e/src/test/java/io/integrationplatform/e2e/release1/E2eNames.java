package io.integrationplatform.e2e.release1;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Row 323 T139/T140: the server names the scenarios call. A name whose item has not landed is {@code TBD(<item>)}
 * and FAILS the step that uses it; swap its one line when the item lands. On-main values cite their source.
 */
public final class E2eNames {

    public static final String BASE_URL_PROPERTY = "e2e.baseUrl";

    static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("intakeApi", "/api/v1/interfaces/{interfaceCode}/messages"), // IntakeController.java:53
            Map.entry("timelineApi", "/api/v1/admin/messages/{trackingId}/timeline"), // base-suite pending-names
            Map.entry("failuresApi", "/api/v1/admin/messages/failures"), // base-suite pending-names
            Map.entry("bulkIntakeApi", "/api/v1/interfaces/{interfaceCode}/messages:bulk"), // IntakeController.java:180
            // POST, one DEAD_LETTERED delivery: DeliveryOperatorEndpointRoles.java:16,21; DeliveryOperatorController.java:61
            Map.entry("replayApi", "/api/v1/admin/messages/deliveries/{deliveryId}/replay"),
            // GET; the day is required (DeliveryReportController @RequestParam day); path: DeliveryReportEndpointRoles.java:15
            Map.entry("dailyReportApi", "/api/v1/reports/delivery?day={day}"),
            // not a server constant: the queue is a registry setting (MqSettings.java:7 host, port, queue) the scenario's own seed creates
            Map.entry("mqTargetQueue", "TBD(T98: queue name is set by the seed via MqSettings.java:7, no fixed name in source)"),
            // tenant B's code is generated per run: MARKER + "-b-" + token (TenantSeedPlan.java:25,54)
            Map.entry("tenantBCodePrefix", "e2e-seed-b-"));

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    private E2eNames() {
    }

    public static String nameOf(String key) {
        return nameOf(NAMES, key);
    }

    static String nameOf(Map<String, String> names, String key) {
        if (!names.containsKey(key)) {
            throw new IllegalArgumentException("E2eNames: unknown name " + key);
        }
        String value = names.get(key);
        if (value == null || value.isBlank() || value.toUpperCase(Locale.ROOT).contains("TBD(")) {
            throw new IllegalStateException(key + " is still " + value
                    + " - swap its one line in E2eNames when that item lands");
        }
        return value;
    }

    /** The backend under test, from {@code -De2e.baseUrl}; loopback only, so a run can never reach production. */
    public static String baseUrl() {
        return baseUrl(System.getProperty(BASE_URL_PROPERTY));
    }

    static String baseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(BASE_URL_PROPERTY
                    + " is not set - waits on T143 (the compose e2e profile that boots the backend)");
        }
        URI uri = URI.create(raw.trim());
        if (uri.getHost() == null || !LOOPBACK.contains(uri.getHost())) {
            throw new IllegalStateException(BASE_URL_PROPERTY
                    + " must be a loopback address (127.0.0.1/localhost), got " + uri.getHost());
        }
        String text = uri.toString();
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }
}
