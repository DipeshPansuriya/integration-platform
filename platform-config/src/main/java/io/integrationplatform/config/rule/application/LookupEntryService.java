package io.integrationplatform.config.rule.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository.RuleSetRow;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The lookup-entry edit path (T-163, E7-F3-S1): an entry of a lookup table - one row of the LOOKUP rule set's
 * decision table - is changed in place, effective immediately. One home for the content (V2_2_51: the JDM lives
 * once in {@code rule_set}), so the change rewrites that one row, is checked by the same {@link DecisionEngine} the
 * authoring path checks with, and the next {@link io.integrationplatform.config.rule.api.RuleEvaluationPort}
 * evaluation - the same read the transform side resolves through - sees it with no publish and no restart: the
 * service caches nothing. Every accepted change publishes exactly one {@link AuditEvent} (who, what, before,
 * after, when) through Spring's {@link ApplicationEventPublisher}; E12's append-only writer subscribes - until
 * then it is not persisted. A read-only user never reaches here: {@link LookupEndpointRoles} maps the endpoints to
 * {@code RolePolicy.EDIT_CONFIGURATION}. Another tenant's (or unknown, or non-lookup) id answers 404.
 */
public class LookupEntryService {

    /** The one action an accepted entry change is audited with. */
    public static final String ACTION = "LOOKUP_ENTRY_CHANGED";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcRuleSetRepository ruleSets;
    private final DecisionEngine engine;
    private final ApplicationEventPublisher events;

    public LookupEntryService(JdbcRuleSetRepository ruleSets, DecisionEngine engine, ApplicationEventPublisher events) {
        this.ruleSets = Objects.requireNonNull(ruleSets, "ruleSets");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** The entries of one lookup table - its decision-table rows as {@code {id, key, value}} - in table order. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String lookupIdText) {
        UUID lookupId = id(lookupIdText);
        JsonNode table = tableOf(JSON.readTree(findLookup(lookupId).content()));
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, String> columns = entryColumns(table);
        for (JsonNode rule : rulesOf(table)) {
            entries.add(entry(rule.path("_id").asString(), literal(cellOf(rule, columns.get("key"))),
                    literal(cellOf(rule, columns.get("value")))));
        }
        return ConfigApiException.body("lookupId", lookupId.toString(), "entries", entries);
    }

    /**
     * Changes one entry in place: {@code {key, value}} replaces the row's input and output cell; the rewritten
     * table is engine-checked like any save; exactly one audit event carries who, what, before, after, when.
     */
    @Transactional
    public Map<String, Object> change(String lookupIdText, String entryId, JsonNode body, LoginBinding actor) {
        UUID lookupId = id(lookupIdText);
        RuleSetRow stored = findLookup(lookupId);
        String key = text(body, "key");
        String value = text(body, "value");
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            throw ConfigApiException.validation("key and value are required");
        }

        JsonNode model = JSON.readTree(stored.content());
        JsonNode table = tableOf(model);
        Map<String, String> columns = entryColumns(table);
        JsonNode found = entryRow(table, entryId);
        if (!(found instanceof ObjectNode row)) {
            throw ConfigApiException.notFound();
        }
        String beforeKey = literal(cellOf(found, columns.get("key")));
        String beforeValue = literal(cellOf(found, columns.get("value")));

        row.put(columns.get("key"), asLiteral(key));
        row.put(columns.get("value"), asLiteral(value));
        String content = model.toString();
        engine.check(content).ifPresent(problem -> {
            throw new RuleTableInvalidException(problem);
        });
        if (ruleSets.update(lookupId, stored.name(), content, actor.userId()) != 1) {
            throw ConfigApiException.notFound();
        }

        events.publishEvent(new AuditEvent(actor.tenantId(), actor.userId().toString(), ACTION,
                List.of(lookupId.toString(), entryId), entryJson(beforeKey, beforeValue), entryJson(key, value),
                Instant.now()));
        return ConfigApiException.body("lookupId", lookupId.toString(), "entryId", entryId, "key", key,
                "value", value);
    }

    // ------------------------------------------------------------------ the table's shape

    /** The rule set of the path id - 404 when it is not visible to the tenant, not a uuid, or not a lookup. */
    private RuleSetRow findLookup(UUID lookupId) {
        RuleSetRow stored = ruleSets.find(lookupId).orElseThrow(ConfigApiException::notFound);
        if (!"LOOKUP".equals(stored.kind())) {
            throw ConfigApiException.notFound();
        }
        return stored;
    }

    /** The decision-table node of the JDM; the engine-checked authoring path guarantees the shape. */
    private static JsonNode tableOf(JsonNode model) {
        for (JsonNode node : model.path("nodes")) {
            if ("decisionTableNode".equals(node.path("type").asString()) && node.path("content").isObject()) {
                return node;
            }
        }
        throw ConfigApiException.server("the lookup table has no decision table");
    }

    private static JsonNode entryRow(JsonNode table, String entryId) {
        for (JsonNode rule : rulesOf(table)) {
            if (entryId.equals(rule.path("_id").asString())) {
                return rule;
            }
        }
        throw ConfigApiException.notFound();
    }

    private static JsonNode rulesOf(JsonNode table) {
        return table.path("content").path("rules");
    }

    /** The input (key) and output (value) column ids - a lookup resolves one key to one value. */
    private static Map<String, String> entryColumns(JsonNode table) {
        JsonNode content = table.path("content");
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("key", singleColumn(content.path("inputs"), "input"));
        columns.put("value", singleColumn(content.path("outputs"), "output"));
        return columns;
    }

    private static String singleColumn(JsonNode columns, String what) {
        List<String> ids = new ArrayList<>();
        for (JsonNode column : columns) {
            String id = column.path("id").asString();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.size() != 1) {
            throw ConfigApiException.validation("a lookup table must have exactly one " + what + " column");
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

    /** The cell as the engine stores it: a quoted ZEN string literal. */
    private static String asLiteral(String value) {
        return "\"" + value + "\"";
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    /** A path id that is not a uuid names no row: the same 404 as a foreign or unknown id, never a 500. */
    private static UUID id(String pathId) {
        try {
            return UUID.fromString(pathId);
        } catch (IllegalArgumentException notAUuid) {
            throw ConfigApiException.notFound();
        }
    }

    private static Map<String, Object> entry(String entryId, String key, String value) {
        return ConfigApiException.body("id", entryId, "key", key, "value", value);
    }

    /** The audit's before/after payload: the entry as key and value, JSON-escaped. */
    private static String entryJson(String key, String value) {
        return "{\"key\":\"" + escape(key) + "\",\"value\":\"" + escape(value) + "\"}";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}