package io.integrationplatform.config.flow.application;

import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.MappingRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.RouteRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one canonical serialization of a flow bundle (T-019: the snapshot the lifecycle walks, T-021: the bundle the
 * publish materializes). It is a read-back of the live rows, in the shape the suites compare byte for byte: an
 * {@code interface} part (code, name, ordering, businessKeyRule), a {@code routes} array ordered by sequence then
 * id (id, sequence, condition, isPrimary, status, targetEndpointId) and a {@code mappings} array ordered by the
 * owning route's sequence then id (id, routeId, fieldMappings, compiledExpression).
 *
 * <p>Being a read-back is the point: the snapshot captures whatever the working rows hold at save time - the
 * byte-for-byte proof then shows the publish lays the captured snapshot, never the drifted working rows.
 */
public final class FlowBundle {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FlowBundle() {
    }

    /** One route entry of a parsed snapshot. */
    public record SnapshotRoute(String id, int sequence, String condition, boolean isPrimary, String status,
            String targetEndpointId) {
    }

    /** One mapping entry of a parsed snapshot; {@code fieldMappingsJson} is the stored JSON text.
     *  The entries carry the mapping's bound contract versions and its pinned mapping_version row
     *  (null when the mapping is still unbound - the publish pin check refuses those). */
    public record SnapshotMapping(String id, String routeId, String fieldMappingsJson, String compiledExpression,
            String sourceContractVersionId, String targetContractVersionId, String mappingVersionId) {
    }

    /** The parsed snapshot the publish materializes. */
    public record Parsed(List<SnapshotRoute> routes, List<SnapshotMapping> mappings) {
    }

    public static String serialize(String code, String name, String ordering, String businessKeyRule,
            List<RouteRow> routes, List<MappingRow> mappings) {
        return serialize(code, name, ordering, businessKeyRule, List.of(), routes, mappings);
    }

    /**
     * T108: as above, with the flow's masked-field paths in the interface part - omitted when there are none, so a
     * flow without masked fields keeps its exact snapshot bytes.
     */
    public static String serialize(String code, String name, String ordering, String businessKeyRule,
            List<String> maskedFields, List<RouteRow> routes, List<MappingRow> mappings) {
        ObjectNode snapshot = JSON.createObjectNode();
        ObjectNode interfacePart = snapshot.putObject("interface");
        interfacePart.put("code", code);
        interfacePart.put("name", name);
        interfacePart.put("ordering", ordering);
        interfacePart.put("businessKeyRule", businessKeyRule);
        if (maskedFields != null && !maskedFields.isEmpty()) {
            ArrayNode masked = interfacePart.putArray("maskedFields");
            maskedFields.forEach(masked::add);
        }

        List<RouteRow> sortedRoutes = new ArrayList<>(routes);
        sortedRoutes.sort(Comparator.comparingInt(RouteRow::sequence).thenComparing(RouteRow::id));
        ArrayNode routesNode = snapshot.putArray("routes");
        for (RouteRow route : sortedRoutes) {
            ObjectNode entry = routesNode.addObject();
            entry.put("id", route.id());
            entry.put("sequence", route.sequence());
            if (route.condition() == null) {
                entry.putNull("condition");
            } else {
                entry.put("condition", route.condition());
            }
            entry.put("isPrimary", route.isPrimary());
            entry.put("status", route.status());
            entry.put("targetEndpointId", route.targetEndpointId());
        }

        ArrayNode mappingsNode = snapshot.putArray("mappings");
        List<MappingRow> sortedMappings = new ArrayList<>(mappings);
        sortedMappings.sort(Comparator
                .comparingInt((MappingRow m) -> sequenceOf(sortedRoutes, m.routeId()))
                .thenComparing(MappingRow::id));
        for (MappingRow mapping : sortedMappings) {
            ObjectNode entry = mappingsNode.addObject();
            entry.put("id", mapping.id());
            entry.put("routeId", mapping.routeId());
            entry.set("fieldMappings", mapping.fieldMappingsJson() == null
                    ? JsonNodeFactory.instance.nullNode()
                    : JSON.readTree(mapping.fieldMappingsJson()));
            entry.put("compiledExpression", mapping.compiledExpression());
            entry.put("sourceContractVersionId", mapping.sourceContractVersionId());
            entry.put("targetContractVersionId", mapping.targetContractVersionId());
            entry.put("mappingVersionId", mapping.mappingVersionId());
        }
        return snapshot.toString();
    }

    /** T108: the masked-field paths a snapshot carries (none when absent). */
    public static List<String> maskedFields(String snapshotJson) {
        List<String> paths = new ArrayList<>();
        for (JsonNode entry : JSON.readTree(snapshotJson).path("interface").path("maskedFields")) {
            paths.add(entry.asString());
        }
        return List.copyOf(paths);
    }

    public static Parsed parse(String snapshotJson) {
        JsonNode snapshot = JSON.readTree(snapshotJson);
        List<SnapshotRoute> routes = new ArrayList<>();
        for (JsonNode entry : snapshot.path("routes")) {
            JsonNode condition = entry.path("condition");
            routes.add(new SnapshotRoute(
                    entry.path("id").asString(),
                    entry.path("sequence").asInt(),
                    condition.isMissingNode() || condition.isNull() ? null : condition.asString(),
                    entry.path("isPrimary").asBoolean(),
                    entry.path("status").asString(),
                    entry.path("targetEndpointId").asString()));
        }
        List<SnapshotMapping> mappings = new ArrayList<>();
        for (JsonNode entry : snapshot.path("mappings")) {
            mappings.add(new SnapshotMapping(
                    entry.path("id").asString(),
                    entry.path("routeId").asString(),
                    entry.path("fieldMappings").isMissingNode() ? "[]" : entry.path("fieldMappings").toString(),
                    entry.path("compiledExpression").asString(),
                    textOrNull(entry.path("sourceContractVersionId")),
                    textOrNull(entry.path("targetContractVersionId")),
                    textOrNull(entry.path("mappingVersionId"))));
        }
        return new Parsed(routes, mappings);
    }

    /** The mapping order follows the owning route; an unseen route sorts after every known one (stable, no NPE). */
    /** A snapshot field that may carry JSON null (an unbound mapping) reads as Java null. */
    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    /** The mapping order follows the owning route; an unseen route sorts after every known one (stable, no NPE). */
    private static int sequenceOf(List<RouteRow> routes, String routeId) {
        for (RouteRow route : routes) {
            if (route.id().equals(routeId)) {
                return route.sequence();
            }
        }
        return Integer.MAX_VALUE;
    }
}
