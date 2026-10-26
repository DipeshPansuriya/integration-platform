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
            Map.entry("bulkIntakeApi", "/api/v1/interfaces/{interfaceCode}/messages:bulk"), // IntakeController.submitBulk (T115, 93aa1910)
            Map.entry("replayApi", "/api/v1/admin/messages/deliveries/{deliveryId}/replay"), // DeliveryOperatorController.java:61 (by DELIVERY id)
            Map.entry("dailyReportApi", "/api/v1/reports/delivery?day={day}"), // DeliveryReportController, DeliveryReportEndpointRoles.REPORT (T-139): the stored roll-up of one UTC day
            Map.entry("mqTargetQueue", "r1.orders.mq"), // the queue the T174b runner seeds target B (MQ-CSV) with
            Map.entry("flowCodeDefault", "REF-ORDER"),
            // tenant B's code is generated per run: MARKER + "-b-" + token (TenantSeedPlan.java:25,54); the run's own code is tenantBCode()
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

    /** The interface the intake is addressed by: the seeded flow's generated code ({@code -De2e.interfaceCode}). */
    public static String interfaceCode() {
        String raw = System.getProperty("e2e.interfaceCode");
        return raw == null || raw.isBlank() ? nameOf("flowCodeDefault") : raw.trim();
    }

    /** Tenant A's code ({@code -De2e.tenantACode}); the runner passes the seeded one. */
    public static String tenantACode() {
        String raw = System.getProperty("e2e.tenantACode");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("e2e.tenantACode is not set - the runner passes the seeded tenant A code");
        }
        return raw.trim();
    }

    /** Tenant B's code ({@code -De2e.tenantBCode}); the runner passes the seeded one, never a guess. */
    public static String tenantBCode() {
        String raw = System.getProperty("e2e.tenantBCode");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("e2e.tenantBCode is not set - the runner passes the seeded tenant B code");
        }
        return raw.trim();
    }

    /** The compose WireMock the backend's target A calls ({@code -De2e.wiremockUrl}); empty = start our own. */
    public static String externalWiremock() {
        String raw = System.getProperty("e2e.wiremockUrl");
        return raw == null || raw.isBlank() ? null : raw.trim().replaceAll("/+$", "");
    }

    /** The running Artemis container of the runner ({@code -De2e.artemisContainer}); empty = start our own. */
    public static String externalArtemisContainer() {
        String raw = System.getProperty("e2e.artemisContainer");
        return raw == null || raw.isBlank() ? null : raw.trim();
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
