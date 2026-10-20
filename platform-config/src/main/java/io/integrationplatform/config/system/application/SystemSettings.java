package io.integrationplatform.config.system.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Each system type's settings schema, shipped in code (ADR-011 Decision 1: validated on every save; a new type is a
 * code change). The schema is a closed declaration per type - required and optional fields with their kind - and the
 * validation lists EVERY error, never only the first. Unknown fields are refused, so nothing else (a password, a
 * token) can ride in the plain {@code settings} column; credentials are a separate concern (lane C, ADR-007).
 *
 * <table>
 *   <caption>Settings per type (T-102 minimal set)</caption>
 *   <tr><th>Type</th><th>Required</th><th>Optional</th></tr>
 *   <tr><td>API</td><td>baseUrl (http/https address with a host, no user info)</td>
 *       <td>timeoutMs (100-300000), followRedirects (boolean, default false)</td></tr>
 *   <tr><td>DB</td><td>host, port (1-65535), database</td><td>-</td></tr>
 *   <tr><td>MQ</td><td>host, port (1-65535), queue</td><td>-</td></tr>
 *   <tr><td>FILE</td><td>rootPath</td><td>-</td></tr>
 * </table>
 *
 * No type and no settings together is the E3 untyped registration (still valid); one without the other is refused.
 */
public final class SystemSettings {

    /** The closed register of system types, in the order the API names them. */
    public static final List<String> TYPES = List.of("API", "DB", "MQ", "FILE");

    static final int MAX_TEXT = 2048;

    private enum Kind { TEXT, PORT, HTTP_URL, TIMEOUT_MS, BOOLEAN, TRIAL_CALLS, ERROR_DICTIONARY }

    private record Field(String name, Kind kind, boolean required) {
    }

    /**
     * T-182/T-183 (ADR-010 sections 5 and 6; ADR-011 Decision 1 addendum 2026-10-20): the two fields every type's
     * settings may carry - the per-target half-open trial cap and the error dictionary that tunes the default
     * outcome table. They live in {@code target_endpoint.settings} (JSONB, validated here on save, no migration).
     */
    private static final List<Field> COMMON = List.of(
            new Field("halfOpenTrialCalls", Kind.TRIAL_CALLS, false),
            new Field("errorDictionary", Kind.ERROR_DICTIONARY, false));

    private static final Map<String, List<Field>> SCHEMAS = schemas();

    private SystemSettings() {
    }

    private static List<Field> schema(Field... typed) {
        List<Field> fields = new ArrayList<>(List.of(typed));
        fields.addAll(COMMON);
        return List.copyOf(fields);
    }

    private static Map<String, List<Field>> schemas() {
        Map<String, List<Field>> schemas = new LinkedHashMap<>();
        schemas.put("API", schema(new Field("baseUrl", Kind.HTTP_URL, true),
                new Field("timeoutMs", Kind.TIMEOUT_MS, false), new Field("followRedirects", Kind.BOOLEAN, false)));
        schemas.put("DB", schema(new Field("host", Kind.TEXT, true), new Field("port", Kind.PORT, true),
                new Field("database", Kind.TEXT, true)));
        schemas.put("MQ", schema(new Field("host", Kind.TEXT, true), new Field("port", Kind.PORT, true),
                new Field("queue", Kind.TEXT, true)));
        schemas.put("FILE", schema(new Field("rootPath", Kind.TEXT, true)));
        return Map.copyOf(schemas);
    }

    /**
     * Every validation error of a (type, settings) pair; empty when valid.
     *
     * @param type     the request's {@code type} node (or {@code null}/missing/JSON null for none)
     * @param settings the request's {@code settings} node (or {@code null}/missing/JSON null for none)
     */
    public static List<String> validate(JsonNode type, JsonNode settings) {
        boolean noType = absent(type);
        boolean noSettings = absent(settings);
        if (noType && noSettings) {
            return List.of();
        }
        if (noType) {
            return List.of("type is required when settings are given (one of " + String.join(", ", TYPES) + ")");
        }
        String typeName = type.isString() ? type.stringValue() : type.toString();
        List<Field> schema = type.isString() ? SCHEMAS.get(typeName) : null;
        if (schema == null) {
            return List.of("type " + clip(typeName) + " is not one of " + String.join(", ", TYPES));
        }
        if (noSettings) {
            return List.of("settings are required for type " + typeName);
        }
        if (!settings.isObject()) {
            return List.of("settings must be a JSON object");
        }
        List<String> errors = new ArrayList<>();
        for (Field field : schema) {
            JsonNode value = settings.get(field.name());
            if (absent(value)) {
                if (field.required()) {
                    errors.add("settings." + field.name() + " is required for type " + typeName);
                }
                continue;
            }
            String problem = problem(field.kind(), value);
            if (problem != null) {
                errors.add("settings." + field.name() + " " + problem);
            }
        }
        for (String name : settings.propertyNames()) {
            if (schema.stream().noneMatch(field -> field.name().equals(name))) {
                errors.add("settings." + clip(name) + " is not a known " + typeName + " setting");
            }
        }
        return List.copyOf(errors);
    }

    /** The upper-case type text of a node, or {@code null} for none. */
    public static String typeOf(JsonNode type) {
        return absent(type) ? null : type.asString();
    }

    private static String problem(Kind kind, JsonNode value) {
        return switch (kind) {
            case TEXT -> !value.isString() || value.stringValue().isBlank() ? "must be a non-blank text"
                    : value.stringValue().length() > MAX_TEXT ? "must be at most " + MAX_TEXT + " characters" : null;
            case PORT -> value.isIntegralNumber() && value.canConvertToInt()
                    && value.intValue() >= 1 && value.intValue() <= 65535 ? null
                    : "must be a whole number from 1 to 65535";
            case TIMEOUT_MS -> value.isIntegralNumber() && value.canConvertToInt()
                    && value.intValue() >= 100 && value.intValue() <= 300_000 ? null
                    : "must be a whole number of milliseconds from 100 to 300000";
            case BOOLEAN -> value.isBoolean() ? null : "must be true or false";
            case HTTP_URL -> httpUrlProblem(value);
            case TRIAL_CALLS -> value.isIntegralNumber() && value.canConvertToInt()
                    && value.intValue() >= 1 && value.intValue() <= 100 ? null
                    : "must be a whole number of trial calls from 1 to 100";
            case ERROR_DICTIONARY -> !value.isObject() ? "must be a JSON object of status to outcome class"
                    : ErrorDictionaryCheck.entryErrors(value).isEmpty() ? null
                            : "refused: " + String.join("; ", ErrorDictionaryCheck.entryErrors(value));
        };
    }

    private static String httpUrlProblem(JsonNode value) {
        String expected = "must be an http or https address with a host";
        if (!value.isString() || value.stringValue().length() > MAX_TEXT) {
            return expected;
        }
        try {
            URI uri = new URI(value.stringValue().trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return expected;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return expected;
            }
            if (uri.getRawUserInfo() != null) {
                return "must not carry user information (credentials are never part of an address)";
            }
            return null;
        } catch (URISyntaxException malformed) {
            return expected;
        }
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    private static String clip(String text) {
        String flat = text.replaceAll("\\p{Cntrl}", "_");
        return flat.length() > 64 ? flat.substring(0, 64) + "..." : flat;
    }
}
