package io.integrationplatform.routing.adapter.db;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-149: a DB target's registry settings - host, port, database (the type's save-side schema) and the adapter
 * extensions the send needs: the optional restricted account's {@code user}, the target {@code table} this
 * endpoint writes. The tables the target owner approved are NOT a setting (E16 F3): they live in
 * {@code db_target_approved_table} and are read through {@link ApprovedTables}. A bad value is a configuration bug
 * (the send refuses it PERMANENT); shape follows {@code MqSettings.fromJson}.
 *
 * <p>shortcut: {@code user} and {@code table} travel inside the DB system's registry JSON
 * because {@code target_endpoint} does not yet carry its own {@code type}/{@code settings} columns (E5's registry
 * extension); move them to the endpoint row (validated against the extended save-side schema) when that lands.
 */
public record DbSettings(String host, int port, String database, String user, String table, String sslmode) {

    /** Without a per-target TLS mode: the sender's configured default applies. */
    public DbSettings(String host, int port, String database, String user, String table) {
        this(host, port, database, user, table, null);
    }

    /** The TLS modes a target may ask for; the unencrypted modes are never a per-target choice (F5). */
    public static final List<String> SSL_MODES = List.of("require", "verify-ca", "verify-full");

    private static final java.util.regex.Pattern DATABASE = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,63}");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public DbSettings {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        if (host.isBlank() || database.isBlank()) {
            throw new IllegalStateException("DB settings need host and database");
        }
        // F2 (CWE-74): defence in depth behind the save-time check - the host and database become parts of a JDBC
        // URL, so nothing but a host name / IP literal and a plain database name may ever get there
        if (!io.integrationplatform.config.destination.DestinationGuard.isHostNameOrIpLiteral(host)) {
            throw new IllegalStateException("the DB host must be a host name or an IP address");
        }
        if (!DATABASE.matcher(database).matches()) {
            throw new IllegalStateException("the DB database must be 1 to 63 letters, digits, dot, hyphen or underscore");
        }
        if (sslmode != null && !SSL_MODES.contains(sslmode)) {
            throw new IllegalStateException("the DB sslmode must be one of " + String.join(", ", SSL_MODES));
        }
        if (user != null && !user.isBlank() && !validIdentifier(user)) {
            throw new IllegalStateException("the DB account must be a plain identifier");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("DB port must be 1-65535");
        }
        if (table != null && !validIdentifier(table)) {
            throw new IllegalStateException("the DB target table '" + table + "' is not a plain identifier");
        }
        if (user != null && user.isBlank()) {
            user = null;
        }
    }

    /** A plain SQL identifier of the approved-table contract (never a quoted expression). */
    public static boolean validIdentifier(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z_0-9]{0,62}");
    }

    public static DbSettings fromJson(String json) {
        JsonNode node = JSON.readTree(json);
        return new DbSettings(text(node, "host"), port(node), text(node, "database"), optionalUser(node),
                optionalTable(node), optionalText(node, "sslmode"));
    }

    private static int port(JsonNode node) {
        JsonNode port = node.get("port");
        if (port == null || !port.isIntegralNumber()) {
            throw new IllegalStateException("DB settings need an integer port");
        }
        if (port.intValue() < 1 || port.intValue() > 65535) {
            throw new IllegalStateException("DB port must be 1-65535");
        }
        return port.intValue();
    }

    private static String text(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (field == null || !field.isString()) {
            throw new IllegalStateException("DB settings need " + name + " as text");
        }
        return field.stringValue();
    }

    private static String optionalUser(JsonNode node) {
        JsonNode field = node.get("user");
        return field == null || field.isNull() ? null : field.stringValue();
    }


    private static String optionalText(JsonNode node, String name) {
        JsonNode field = node.get(name);
        return field == null || field.isNull() ? null : field.stringValue();
    }
    /** The endpoint's chosen table - required on the send path, absent on the test-connection path. */
    private static String optionalTable(JsonNode node) {
        JsonNode field = node.get("table");
        if (field == null || field.isNull()) {
            return null;
        }
        String table = field.stringValue();
        if (table == null || table.isBlank()) {
            throw new IllegalStateException("DB settings need a non-blank table");
        }
        return table.toUpperCase(Locale.ROOT).equals(table.toLowerCase(Locale.ROOT)) ? table : table;
    }
}
