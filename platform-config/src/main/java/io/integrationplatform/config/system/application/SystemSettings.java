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
 *   <tr><td>DB</td><td>host, port (1-65535), database, user (E16 C3: the restricted account)</td><td>table (plain identifier; E16).
 *       The approved tables are NOT a setting (E16 F3): a save carrying {@code approvedTables} is refused
 *       {@value #FIELD_NOT_ALLOWED}; they are requested and approved through the approved-tables API</td></tr>
 *   <tr><td>MQ</td><td>host, port (1-65535), queue</td><td>contract (T176: MQ-CSV layout, at most 200 columns)</td></tr>
 *   <tr><td>FILE</td><td>rootPath</td><td>fileNamePattern (plain name; {deliveryId}, {ext}; E16)</td></tr>
 *   <tr><td>KAFKA</td><td>bootstrapServers (host:port list), topic (never a platform topic)</td>
 *       <td>securityProtocol (PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL; absent = {@value #KAFKA_DEFAULT_PROTOCOL} at
 *       send), saslMechanism (PLAIN, SCRAM-SHA-256, SCRAM-SHA-512; required with SASL), username (required with SASL),
 *       truststore (a file NAME in the operator's truststore directory, never a path; TLS only)</td></tr>
 * </table>
 *
 * Every type also accepts {@code halfOpenTrialCalls} (T-183, 1-100, default 3 when absent): the breaker's half-open
 * trial-call count (ADR-011 Decision 1).
 *
 * No type and no settings together is the E3 untyped registration (still valid); one without the other is refused.
 */
public final class SystemSettings {

    /** The closed register of system types, in the order the API names them. */
    public static final List<String> TYPES = List.of("API", "DB", "MQ", "FILE", "KAFKA");

    static final int MAX_TEXT = 2048;

    /** E16 F3: the refusal code of a settings key that moved out of the settings (a save carrying it is 400). */
    public static final String FIELD_NOT_ALLOWED = "FIELD_NOT_ALLOWED";

    private static final String APPROVED_TABLES = "approvedTables";

    /** E16 F3: whether {@code name} is a plain SQL identifier (a DB target's table name). */
    public static boolean isIdentifier(String name) {
        return name != null && IDENTIFIER.matcher(name).matches();
    }

    /** E16: the protocol a KAFKA system without {@code securityProtocol} is dialled with - TLS, never clear text. */
    public static final String KAFKA_DEFAULT_PROTOCOL = "SSL";

    /** E16: the closed sets the KAFKA security fields accept; the Kafka adapter reads the same names. */
    public static final List<String> KAFKA_PROTOCOLS = List.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");

    public static final List<String> KAFKA_SASL_MECHANISMS = List.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");

    private static final java.util.regex.Pattern KAFKA_USER = java.util.regex.Pattern.compile("[A-Za-z0-9._@$:=+-]{1,256}");

    private static final java.util.regex.Pattern TRUSTSTORE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** E16: whether {@code name} is an acceptable Kafka SASL user name (the adapter re-checks a stored row). */
    public static boolean isKafkaUser(String name) {
        return name != null && KAFKA_USER.matcher(name).matches();
    }

    /** E16: whether {@code name} is a plain truststore file name - no path, so it cannot leave its directory. */
    public static boolean isTruststoreName(String name) {
        return name != null && TRUSTSTORE_NAME.matcher(name).matches();
    }

    private enum Kind {
        TEXT, PORT, HTTP_URL, TIMEOUT_MS, BOOLEAN, TRIAL_CALLS, ERROR_DICTIONARY,
        /** E16: a plain SQL identifier (the DB target's account and table). */
        IDENTIFIER,
        /** E16: comma-separated host:port list (the Kafka target's bootstrap servers). */
        BOOTSTRAP_SERVERS,
        /** E16: a Kafka topic that is not one of the platform's own (T-184 SF-2). */
        KAFKA_TOPIC,
        /** E16: a plain file name with only the {deliveryId} and {ext} placeholders (the FILE target's name). */
        FILE_NAME_PATTERN,
        /** E16 F5: the DB target's optional TLS mode, a closed list of encrypted modes. */
        SSL_MODE,
        /** E16: one of {@link #KAFKA_PROTOCOLS}. */
        KAFKA_PROTOCOL,
        /** E16: one of {@link #KAFKA_SASL_MECHANISMS}. */
        KAFKA_MECHANISM,
        /** E16: the SASL user name (the password is the vault's KAFKA_TOPIC credential, never a setting). */
        KAFKA_USER,
        /** E16: a truststore file name - no path separator, so it cannot leave the operator's directory. */
        TRUSTSTORE_NAME,
        /** T176: the MQ-CSV target's typed, bounded CSV layout (read by the routing module's CsvContractSettings). */
        CSV_CONTRACT
    }

    /** T176: at most this many columns in an MQ-CSV contract. */
    static final int MAX_CONTRACT_COLUMNS = 200;

    private static final Set<String> CONTRACT_KEYS = Set.of("columns", "delimiter", "quote", "quoteAll", "encoding",
            "lineEnding", "header");

    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z_0-9]{0,62}");

    private record Field(String name, Kind kind, boolean required) {
    }

    /** ADR-007 Decision 4, ADR-010 section 5: whatever a dictionary says, these are never retried (R13). */
    private static final Set<Integer> NEVER_RETRYABLE = Set.of(400, 401, 403, 422);

    private static final Set<String> DICTIONARY_CLASSES = Set.of("TRANSIENT", "AMBIGUOUS", "PERMANENT");

    private static final Field TRIAL_CALLS = new Field("halfOpenTrialCalls", Kind.TRIAL_CALLS, false);

    /** F5: the TLS modes a DB target may ask for; no unencrypted mode can be chosen per target. */
    static final List<String> DB_SSL_MODES = List.of("require", "verify-ca", "verify-full");

    private static final java.util.regex.Pattern DB_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,63}");

    private static final Map<String, List<Field>> SCHEMAS = schemas();

    private SystemSettings() {
    }

    private static Map<String, List<Field>> schemas() {
        Map<String, List<Field>> schemas = new LinkedHashMap<>();
        schemas.put("API", List.of(new Field("baseUrl", Kind.HTTP_URL, true),
                new Field("timeoutMs", Kind.TIMEOUT_MS, false), new Field("followRedirects", Kind.BOOLEAN, false),
                new Field("errorDictionary", Kind.ERROR_DICTIONARY, false), TRIAL_CALLS));
        schemas.put("DB", List.of(new Field("host", Kind.TEXT, true), new Field("port", Kind.PORT, true),
                new Field("database", Kind.TEXT, true), new Field("user", Kind.IDENTIFIER, true),
                new Field("table", Kind.IDENTIFIER, false), TRIAL_CALLS));
        List<Field> dbWithTls = new ArrayList<>(schemas.get("DB")); // F5: optional encrypted-only sslmode
        dbWithTls.add(new Field("sslmode", Kind.SSL_MODE, false));
        schemas.put("DB", List.copyOf(dbWithTls));
        schemas.put("MQ", List.of(new Field("host", Kind.TEXT, true), new Field("port", Kind.PORT, true),
                new Field("queue", Kind.TEXT, true), new Field("contract", Kind.CSV_CONTRACT, false), TRIAL_CALLS));
        schemas.put("FILE", List.of(new Field("rootPath", Kind.TEXT, true),
                new Field("fileNamePattern", Kind.FILE_NAME_PATTERN, false), TRIAL_CALLS));
        schemas.put("KAFKA", List.of(new Field("bootstrapServers", Kind.BOOTSTRAP_SERVERS, true),
                new Field("topic", Kind.KAFKA_TOPIC, true), new Field("securityProtocol", Kind.KAFKA_PROTOCOL, false),
                new Field("saslMechanism", Kind.KAFKA_MECHANISM, false), new Field("username", Kind.KAFKA_USER, false),
                new Field("truststore", Kind.TRUSTSTORE_NAME, false), TRIAL_CALLS));
        return Map.copyOf(schemas);
    }

    /**
     * Every validation error of a (type, settings) pair; empty when valid.
     *
     * @param type     the request's {@code type} node (or {@code null}/missing/JSON null for none)
     * @param settings the request's {@code settings} node (or {@code null}/missing/JSON null for none)
     */
    public static List<String> validate(JsonNode type, JsonNode settings) {
        return validate(type, settings, false);
    }

    /**
     * As above; {@code allowKafkaPlaintext} is the operator's {@code platform.config.destinations.kafka-allow-plaintext}
     * (E16 R1/R2): false (the default) refuses a tenant's PLAINTEXT and SASL_PLAINTEXT.
     */
    public static List<String> validate(JsonNode type, JsonNode settings, boolean allowKafkaPlaintext) {
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
            if (field.kind() == Kind.CSV_CONTRACT) {
                errors.addAll(csvContractProblems(value));
                continue;
            }
            String problem = problem(field.kind(), value);
            if (problem == null && typeName.equals("DB")) {
                problem = dbTextProblem(field.name(), value); // F2: host and database become parts of a JDBC URL
            }
            if (problem != null) {
                errors.add("settings." + field.name() + " " + problem);
            }
        }
        if (typeName.equals("KAFKA")) {
            errors.addAll(kafkaSecurityProblems(settings));
            if (!allowKafkaPlaintext && settings.get("securityProtocol") != null
                    && settings.get("securityProtocol").isString()
                    && settings.get("securityProtocol").stringValue().endsWith("PLAINTEXT")) {
                errors.add("settings.securityProtocol must be SSL or SASL_SSL; clear text is off unless the operator "
                        + "sets platform.config.destinations.kafka-allow-plaintext");
            }
        }
        for (String name : settings.propertyNames()) {
            if (typeName.equals("DB") && name.equals(APPROVED_TABLES)) {
                errors.add("settings." + APPROVED_TABLES + " is not allowed (" + FIELD_NOT_ALLOWED + "): the tables a"
                        + " DB target may write to are requested and approved at"
                        + " /api/v1/admin/systems/{systemId}/approved-tables, never listed in the settings");
            } else if (schema.stream().noneMatch(field -> field.name().equals(name))) {
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
            case IDENTIFIER -> value.isString() && IDENTIFIER.matcher(value.stringValue()).matches() ? null
                    : "must be a plain identifier (letters, digits, underscore)";
            case SSL_MODE -> value.isString() && DB_SSL_MODES.contains(value.stringValue()) ? null
                    : "must be one of " + String.join(", ", DB_SSL_MODES);
            case BOOTSTRAP_SERVERS -> bootstrapServersProblem(value);
            case KAFKA_PROTOCOL -> value.isString() && KAFKA_PROTOCOLS.contains(value.stringValue()) ? null
                    : "must be one of " + String.join(", ", KAFKA_PROTOCOLS);
            case KAFKA_MECHANISM -> value.isString() && KAFKA_SASL_MECHANISMS.contains(value.stringValue()) ? null
                    : "must be one of " + String.join(", ", KAFKA_SASL_MECHANISMS);
            case KAFKA_USER -> value.isString() && KAFKA_USER.matcher(value.stringValue()).matches() ? null
                    : "must be a plain user name (letters, digits and . _ @ $ : = + -), at most 256 characters";
            case TRUSTSTORE_NAME -> value.isString() && TRUSTSTORE_NAME.matcher(value.stringValue()).matches() ? null
                    : "must be a file name (letters, digits, . _ -), never a path";
            case KAFKA_TOPIC -> value.isString()
                    ? io.integrationplatform.config.destination.SystemTopicRefusal.topicProblem(value.stringValue()).orElse(null)
                    : "must be a topic name";
            case FILE_NAME_PATTERN -> fileNamePatternProblem(value);
            case CSV_CONTRACT -> throw new IllegalStateException("validated by csvContractProblems");
        };
    }

    /** T176: the MQ-CSV layout, every field required (as the reader requires them); plain column names, bounded. */
    private static List<String> csvContractProblems(JsonNode contract) {
        if (!contract.isObject()) {
            return List.of("settings.contract must be a JSON object");
        }
        List<String> errors = new ArrayList<>();
        JsonNode columns = contract.get("columns");
        if (absent(columns) || !columns.isArray() || columns.isEmpty() || columns.size() > MAX_CONTRACT_COLUMNS) {
            errors.add("settings.contract.columns must be a list of 1 to " + MAX_CONTRACT_COLUMNS + " columns");
        } else {
            java.util.Map<String, Integer> firstSeen = new java.util.HashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                JsonNode column = columns.get(i);
                String at = "settings.contract.columns[" + i + "]";
                JsonNode name = column.get("name");
                JsonNode type = column.get("type");
                if (!column.isObject() || column.size() != 2) {
                    errors.add(at + " must be an object with exactly name and type");
                    continue;
                }
                if (absent(name) || !name.isString() || !IDENTIFIER.matcher(name.stringValue()).matches()) {
                    errors.add(at + ".name must be a plain identifier (letters, digits, underscore)");
                } else {
                    // T189 (T176): the CSV header and the column mapping are by name - a repeated name is ambiguous
                    Integer first = firstSeen.putIfAbsent(name.stringValue().toLowerCase(Locale.ROOT), i);
                    if (first != null) {
                        errors.add(at + ".name is a duplicate of settings.contract.columns[" + first
                                + "].name (column names must be unique, ignoring case)");
                    }
                }
                if (absent(type) || !type.isString() || !List.of("TEXT", "NUMBER")
                        .contains(type.stringValue().toUpperCase(Locale.ROOT))) {
                    errors.add(at + ".type must be TEXT or NUMBER");
                }
            }
        }
        String delimiter = oneChar(contract, "delimiter", errors);
        String quote = oneChar(contract, "quote", errors);
        if (delimiter != null && delimiter.equals(quote)) {
            errors.add("settings.contract.delimiter and settings.contract.quote must differ");
        }
        for (String flag : List.of("quoteAll", "header")) {
            if (absent(contract.get(flag)) || !contract.get(flag).isBoolean()) {
                errors.add("settings.contract." + flag + " must be true or false");
            }
        }
        JsonNode encoding = contract.get("encoding");
        if (absent(encoding) || !encoding.isString() || !charsetKnown(encoding.stringValue())) {
            errors.add("settings.contract.encoding must be a known character set name (for example UTF-8)");
        }
        JsonNode lineEnding = contract.get("lineEnding");
        if (absent(lineEnding) || !lineEnding.isString()
                || !List.of("LF", "CRLF").contains(lineEnding.stringValue().toUpperCase(Locale.ROOT))) {
            errors.add("settings.contract.lineEnding must be LF or CRLF");
        }
        for (String key : contract.propertyNames()) {
            if (!CONTRACT_KEYS.contains(key)) {
                errors.add("settings.contract." + clip(key) + " is not a known contract setting");
            }
        }
        return errors;
    }

    private static String oneChar(JsonNode contract, String name, List<String> errors) {
        JsonNode value = contract.get(name);
        if (absent(value) || !value.isString() || value.stringValue().length() != 1
                || value.stringValue().indexOf(10) >= 0 || value.stringValue().indexOf(13) >= 0) {
            errors.add("settings.contract." + name + " must be one character (not a line break)");
            return null;
        }
        return value.stringValue();
    }

    private static boolean charsetKnown(String name) {
        try {
            return name.length() <= 40 && java.nio.charset.Charset.isSupported(name);
        } catch (java.nio.charset.IllegalCharsetNameException illegal) {
            return false;
        }
    }

    private static String fileNamePatternProblem(JsonNode value) {
        String expected = "must be a plain file name (at most 128 characters, no folder separator) using only"
                + " the {deliveryId} and {ext} placeholders";
        if (!value.isString()) {
            return expected;
        }
        String name = value.stringValue();
        String bare = name.replace("{deliveryId}", "").replace("{ext}", "");
        boolean plain = !name.isBlank() && name.length() <= 128 && !name.equals(".") && !name.equals("..")
                && bare.indexOf('/') < 0 && bare.indexOf('\\') < 0 && bare.indexOf(':') < 0
                && bare.indexOf('{') < 0 && bare.indexOf('}') < 0;
        if (plain && !name.contains("{deliveryId}")) { // T178c: a taken name is PERMANENT, so a fixed name delivers once
            return "must contain {deliveryId}: a file name that repeats would dead-letter every delivery after the first";
        }
        return plain ? null : expected;
    }

    /** F2 (CWE-74): the DB host is a host name or IP literal and the database a plain name - no URL syntax. */
    private static String dbTextProblem(String field, JsonNode value) {
        if (field.equals("host") && value.isString()
                && !io.integrationplatform.config.destination.DestinationGuard.isHostNameOrIpLiteral(value.stringValue())) {
            return "must be a host name or an IP address (no port, path, query or other URL characters)";
        }
        if (field.equals("database") && value.isString() && !DB_NAME.matcher(value.stringValue()).matches()) {
            return "must be 1 to 63 letters, digits, dot, hyphen or underscore";
        }
        return null;
    }

    /**
     * E16: the cross-field rules of the KAFKA client security. SASL needs a mechanism and a user name and nothing else
     * may carry them; PLAIN is refused over SASL_PLAINTEXT (the password would cross the wire in clear); a truststore
     * needs a TLS protocol. An absent protocol is {@link #KAFKA_DEFAULT_PROTOCOL}.
     */
    private static List<String> kafkaSecurityProblems(JsonNode settings) {
        JsonNode protocolNode = settings.get("securityProtocol");
        String protocol = absent(protocolNode) ? KAFKA_DEFAULT_PROTOCOL
                : protocolNode.isString() ? protocolNode.stringValue() : null;
        if (protocol == null || !KAFKA_PROTOCOLS.contains(protocol)) {
            return List.of(); // the field's own error is reported
        }
        boolean sasl = protocol.startsWith("SASL_");
        boolean tls = protocol.endsWith("SSL");
        List<String> errors = new ArrayList<>();
        JsonNode mechanism = settings.get("saslMechanism");
        if (absent(mechanism) && sasl) {
            errors.add("settings.saslMechanism is required with securityProtocol " + protocol);
        } else if (!absent(mechanism) && !sasl) {
            errors.add("settings.saslMechanism needs securityProtocol SASL_SSL or SASL_PLAINTEXT");
        } else if (sasl && protocol.equals("SASL_PLAINTEXT") && mechanism.isString()
                && mechanism.stringValue().equals("PLAIN")) {
            errors.add("settings.saslMechanism PLAIN over SASL_PLAINTEXT would send the password in clear; "
                    + "use SASL_SSL or a SCRAM mechanism");
        }
        JsonNode username = settings.get("username");
        if (absent(username) && sasl) {
            errors.add("settings.username is required with securityProtocol " + protocol);
        } else if (!absent(username) && !sasl) {
            errors.add("settings.username needs securityProtocol SASL_SSL or SASL_PLAINTEXT");
        }
        if (!absent(settings.get("truststore")) && !tls) {
            errors.add("settings.truststore needs a TLS securityProtocol (SSL or SASL_SSL)");
        }
        return errors;
    }


    private static String bootstrapServersProblem(JsonNode value) {
        String expected = "must be a comma-separated list of host:port, port 1 to 65535";
        if (!value.isString() || value.stringValue().length() > MAX_TEXT) {
            return expected;
        }
        for (String server : value.stringValue().split(",", -1)) {
            String entry = server.strip();
            int at = entry.lastIndexOf(':');
            if (at <= 0 || entry.substring(0, at).isBlank() || entry.substring(0, at).matches(".*[\\s/@].*")) {
                return expected;
            }
            try {
                int port = Integer.parseInt(entry.substring(at + 1));
                if (port < 1 || port > 65535) {
                    return expected;
                }
            } catch (NumberFormatException notAPort) {
                return expected;
            }
        }
        return null;
    }

    /** The bootstrap hosts of a Kafka system's {@code bootstrapServers} text (entries without a host are skipped). */
    public static List<String> bootstrapHosts(String bootstrapServers) {
        List<String> hosts = new ArrayList<>();
        for (String server : bootstrapServers.split(",", -1)) {
            String entry = server.strip();
            int at = entry.lastIndexOf(':');
            if (at > 0 && !entry.substring(0, at).isBlank()) {
                hosts.add(entry.substring(0, at).strip());
            }
        }
        return List.copyOf(hosts);
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
