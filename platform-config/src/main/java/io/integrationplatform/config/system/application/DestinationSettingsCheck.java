package io.integrationplatform.config.system.application;

import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.config.destination.Refusal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * T-124 save time (ADR-010 section 8): every target type's host field goes through the ONE destination guard when a
 * system is saved - API {@code settings.baseUrl}, MQ and DB {@code settings.host}, every KAFKA bootstrap server;
 * a FILE root has no host. No DNS
 * query (the guard's {@link DestinationGuard#checkHost}). A malformed or missing host is the schema's error
 * ({@link SystemSettings#validate}) and is not repeated here.
 */
public final class DestinationSettingsCheck {

    private DestinationSettingsCheck() {
    }

    /** The refusal of the type's host field, as one error naming the field and the reason; empty when allowed. */
    public static List<String> errors(String type, JsonNode settings, DestinationGuard guard) {
        if (type == null || settings == null) {
            return List.of();
        }
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.equals("KAFKA")) {
            return kafkaErrors(settings, guard);
        }
        String field = upper.equals("API") ? "baseUrl" : upper.equals("MQ") || upper.equals("DB") ? "host" : null;
        JsonNode value = field == null ? null : settings.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            return List.of();
        }
        String host = field.equals("baseUrl") ? hostOf(value.stringValue()) : value.stringValue().trim();
        if (host == null) {
            return List.of();
        }
        try {
            guard.checkHost(host);
            return List.of();
        } catch (DestinationRefusedException refused) {
            return List.of("settings." + field + " is refused: " + reason(refused));
        }
    }

    /** E16 (T-148): every bootstrap server's host goes through the one guard; the first refusal is reported. */
    private static List<String> kafkaErrors(JsonNode settings, DestinationGuard guard) {
        JsonNode value = settings.get("bootstrapServers");
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            return List.of();
        }
        for (String host : SystemSettings.bootstrapHosts(value.stringValue())) {
            try {
                guard.checkHost(host);
            } catch (DestinationRefusedException refused) {
                return List.of("settings.bootstrapServers is refused: " + reason(refused));
            }
        }
        return List.of();
    }

    private static String hostOf(String address) {
        try {
            URI uri = new URI(address.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean http = scheme.equals("http") || scheme.equals("https");
            return http && uri.getRawUserInfo() == null && uri.getHost() != null && !uri.getHost().isBlank()
                    ? uri.getHost() : null;
        } catch (URISyntaxException malformed) {
            return null;
        }
    }

    private static String reason(DestinationRefusedException refused) {
        if (refused.refusal() == Refusal.NOT_ON_ALLOW_LIST) {
            return "the host is not on the destination allow-list";
        }
        return refused.detail() == null ? refused.refusal().name()
                : "the host is a refused address (" + refused.detail() + ")";
    }
}
