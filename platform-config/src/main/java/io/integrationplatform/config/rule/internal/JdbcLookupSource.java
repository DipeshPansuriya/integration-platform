package io.integrationplatform.config.rule.internal;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import io.integrationplatform.transform.api.LookupSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E7 lane B2: the config side of the {@link LookupSource} seam - {@code $lookup("<table>", "<key>")} in a mapping's
 * compiled expression resolves through THIS read, i.e. the same lookup entries the lookup-entry editor writes
 * immediately (V2_2_51: the JDM lives once in {@code rule_set}; a LOOKUP rule set's decision table is the table,
 * the key column its one input, the value column its one output). Like the edit path, this resolves nothing ahead
 * of time and caches nothing: the next evaluation sees an accepted change with no publish and no restart.
 */
public class JdbcLookupSource extends TenantScopedRepository implements LookupSource {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String READ_CONTENT_OF_LOOKUP = "select"
            + " (select rs.content::text from public.rule_set rs"
            + "   where rs.tenant_id = lt.tenant_id and rs.id = lt.rule_set_id and rs.kind = 'LOOKUP'"
            + "   limit 1) as content"
            + " from public.lookup_table lt";

    public JdbcLookupSource(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        super(jdbcClient, failClosed(tenantContexts));
    }

    private static TenantContext failClosed(ObjectProvider<TenantContext> tenantContexts) {
        return Objects.requireNonNull(tenantContexts, "tenantContexts")
                .getIfAvailable(() -> () -> Optional.empty());
    }

    @Override
    public String entry(String tableName, String key) {
        List<String> contents = query(READ_CONTENT_OF_LOOKUP, "lt.name = :name", Map.of("name", tableName),
                (row, number) -> row.getString("content"));
        if (contents.isEmpty()) {
            return null; // unknown table: the transform answers a null entry, never a refusal
        }
        return valueOfKey(contents.getFirst(), key);
    }

    /** The value cell of the key's row in the table's JDM, or null when no row matches. */
    private static String valueOfKey(String content, String key) {
        JsonNode table = tableOf(JSON.readTree(content));
        if (table == null) {
            return null; // a malformed lookup JDM resolves to nothing; the engine's own error path is the refusal
        }
        Map<String, String> columns = entryColumns(table);
        for (JsonNode rule : rulesOf(table)) {
            if (key != null && key.equals(literal(cellOf(rule, columns.get("key"))))) {
                return literal(cellOf(rule, columns.get("value")));
            }
        }
        return null;
    }

    // this file mirrors LookupEntryService's already-checked parse of the lookup JDM (the shapes are fixed there)

    private static JsonNode tableOf(JsonNode model) {
        for (JsonNode node : model.path("nodes")) {
            if ("decisionTableNode".equals(node.path("type").asString()) && node.path("content").isObject()) {
                return node;
            }
        }
        return null;
    }

    private static JsonNode rulesOf(JsonNode table) {
        return table == null ? null : table.path("content").path("rules");
    }

    /** The input (key) and output (value) column ids - a lookup resolves one key to one value. */
    private static Map<String, String> entryColumns(JsonNode table) {
        JsonNode content = table.path("content");
        return Map.of(
                "key", singleColumn(content.path("inputs"), "input"),
                "value", singleColumn(content.path("outputs"), "output"));
    }

    private static String singleColumn(JsonNode columns, String what) {
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode column : columns) {
            String id = column.path("id").asString();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.size() != 1) {
            throw new IllegalStateException("a lookup table must have exactly one " + what + " column");
        }
        return ids.getFirst();
    }

    private static String cellOf(JsonNode rule, String columnId) {
        return rule.path(columnId).asString();
    }

    /** The key or value as the configurator wrote it: the quoted ZEN literal's inner text, else the raw cell. */
    private static String literal(String cell) {
        return cell.length() >= 2 && cell.charAt(0) == '"' && cell.charAt(cell.length() - 1) == '"'
                ? cell.substring(1, cell.length() - 1)
                : cell;
    }
}