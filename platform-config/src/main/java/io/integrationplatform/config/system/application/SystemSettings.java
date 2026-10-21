package io.integrationplatform.config.system.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *       <td>timeoutMs (100-300000), followRedirects (boolean, default false), errorDictionary (T-182:
 *       status 100-599 to TRANSIENT, AMBIGUOUS or PERMANENT; never a retryable class for 400, 401, 403, 422)</td></tr>
 *   <tr><td>DB</td><td>host, port (1-65535), database</td><td>-</td></tr>
 *   <tr><td>MQ</td><td>host, port (1-65535), queue</td><td>-</td></tr>
 *   <tr><td>FILE</td><td>rootPath</td><td>-</td></tr>
 * </table>
 *
 * Every type also accepts {@code halfOpenTrialCalls} (T-183, 1-100, default 3 when absent): the breaker's half-open
 * trial-call count (ADR-011 Decision 1).
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

    /** ADR-007 Decision 4, ADR-010 section 5: whatever a dictionary says, these are never retried (R13). */
    private static final Set<Integer> NEVER_RETRYABLE = Set.of(400, 401, 403, 422);

    private static final Set<String> DICTIONARY_CLASSES = Set.of("TRANSIENT", "AMBIGUOUS", "PERMANENT");

    private static final Field TRIAL_CALLS = new Field("halfOpenTrialCalls", Kind.TRIAL_CALLS, false);

    private static final Map<String, List<Field>> SCHEMAS = schemas();

    private SystemSettings() {
    }

    private static Map<String, List<Field>> schemas() {
        Map<String, List<Field>> schemas = new LinkedHashMap<>();
        schemas.put("API", List.of(new Field("baseUrl", Kind.HTTP_URL, true),
                new Field("timeoutMs", Kind.TIMEOUT_MS, false), new Field("followRedirects", Kind.BOOLEAN, false),
                new Field("errorDictionary", Kind.ERROR_DICTIONARY, false), TRIAL_CALLS));
        schemas.put("DB", List.of(new Field("host", Kind.TEXT, true), new Field("port", Kind.PORT, true),
                new Field("database", Kind.TEXT, true), TRIAL_CALLS));
        schemas.put("MQ", List.of(new Field("host", Kind.TEXT, true), new Field("port", Kind.PORT, true),
                new Field("queue", Kind.TEXT, true), TRIAL_CALLS));
        schemas.put("FILE", List.of(new Field("rootPath", Kind.TEXT, true), TRIAL_CALLS));
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
            if (field.kind() == Kind.ERROR_DICTIONARY) {
                errors.addAll(dictionaryProblems(value));
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
            case TRIAL_CALLS -> value.isIntegralNumber() && value.canConvertToInt()
                    && value.intValue() >= 1 && value.intValue() <= 100 ? null
                    : "must be a whole number of trial calls from 1 to 100";
            case ERROR_DICTIONARY -> throw new IllegalStateException("validated by dictionaryProblems");
            case HTTP_URL -> httpUrlProblem(value);
        };
    }

    /**
     * T-182 (ADR-010 section 5): the error dictionary re-classes a status for this target only; every bad entry is
     * listed. 400, 401, 403 and 422 may only stay PERMANENT - a save that makes one retryable is refused.
     */
    private static List<String> dictionaryProblems(JsonNode dictionary) {
        if (!dictionary.isObject()) {
            return List.of("settings.errorDictionary must be a JSON object of status to class");
        }
        List<String> errors = new ArrayList<>();
        for (String key : dictionary.propertyNames()) {
            String entry = "settings.errorDictionary." + clip(key);
            Integer status = statusOf(key);
            if (status == null) {
                errors.add(entry + " is not an HTTP status from 100 to 599");
                continue;
            }
            JsonNode value = dictionary.get(key);
            String outcome = value.isString() ? value.stringValue() : null;
            if (outcome == null || !DICTIONARY_CLASSES.contains(outcome)) {
                errors.add(entry + " must be one of " + String.join(", ", new java.util.TreeSet<>(DICTIONARY_CLASSES)));
            } else if (NEVER_RETRYABLE.contains(status) && !outcome.equals("PERMANENT")) {
                errors.add(entry + " is never retryable (400, 401, 403 and 422 stay PERMANENT whatever the dictionary says)");
            }
        }
        return errors;
    }

    private static Integer statusOf(String key) {
        try {
            int status = Integer.parseInt(key);
            return status >= 100 && status <= 599 && String.valueOf(status).equals(key) ? status : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
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
