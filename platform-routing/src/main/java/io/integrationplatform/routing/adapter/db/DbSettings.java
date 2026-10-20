package io.integrationplatform.routing.adapter.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-149: a DB target's registry settings - host, port, database (the type's save-side schema) and the adapter
 * extensions the send needs: the optional restricted account's {@code user}, the target {@code table} this
 * endpoint writes and the {@code approvedTables} the target owner approved. A bad value is a configuration bug
 * (the send refuses it PERMANENT); shape follows {@code MqSettings.fromJson}.
 *
 * <p>shortcut: {@code user}, {@code table} and {@code approvedTables} travel inside the DB system's registry JSON
 * because {@code target_endpoint} does not yet carry its own {@code type}/{@code settings} columns (E5's registry
 * extension); move them to the endpoint row (validated against the extended save-side schema) when that lands.
 */
public record DbSettings(String host, int port, String database, String user, String table,
        List<String> approvedTables) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public DbSettings {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        if (host.isBlank() || database.isBlank()) {
            throw new IllegalStateException("DB settings need host and database");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("DB port must be 1-65535");
        }
        if (table != null && !validIdentifier(table)) {
            throw new IllegalStateException("the DB target table '" + table + "' is not a plain identifier");
        }
        approvedTables = approvedTables == null ? List.of() : List.copyOf(approvedTables);
        if (!approvedTables.stream().allMatch(DbSettings::validIdentifier)) {
            throw new IllegalStateException("the DB target's approved tables must be plain identifiers");
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
                optionalTable(node), approvedTables(node));
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

    private static List<String> approvedTables(JsonNode node) {
        JsonNode field = node.get("approvedTables");
        if (field == null || field.isNull()) {
            return List.of();
        }
        if (!field.isArray()) {
            throw new IllegalStateException("DB settings need approvedTables as an array");
        }
        List<String> approved = new ArrayList<>();
        for (JsonNode name : field) {
            approved.add(name.isString() ? name.stringValue() : name.toString());
        }
        return approved;
    }
}