package io.integrationplatform.app.composition;

import io.integrationplatform.routing.adapter.api.ApiTargetBody;
import io.integrationplatform.routing.adapter.db.DbRows;
import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E16 content wiring (decide-by-default, reversible by revert): the DB adapter's rows are the delivery's transformed
 * payload - the same bytes the Kafka/MQ/API targets send ({@code transformedApiBody}: pinned mapping version, one
 * transform inside the call's tenant transaction). A JSON object is one row, an array of objects is many; the
 * column names are the mapping's target fields (the object's keys) and are checked by {@code DbMessage}'s
 * plain-identifier rules, so this adds no trust. Anything that is not a flat row set (empty array, scalar, nested
 * cell value, malformed JSON) is no rows: the adapter then refuses PERMANENT and nothing is written. No cell value
 * is ever logged.
 */
final class TransformedDbRows implements DbRows {

    private static final Logger log = LoggerFactory.getLogger(TransformedDbRows.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApiTargetBody body;

    TransformedDbRows(ApiTargetBody body) {
        this.body = Objects.requireNonNull(body, "body");
    }

    @Override
    public Optional<List<Map<String, ?>>> rowsFor(Stage2Send send) {
        byte[] bytes = body.bodyFor(send).map(ApiTargetBody.ApiWireBody::bytes).orElse(null);
        if (bytes == null) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (JacksonException malformed) {
            log.warn("delivery {}: the transform output is not JSON, so there are no DB rows", send.deliveryId());
            return Optional.empty();
        }
        List<JsonNode> objects = new ArrayList<>();
        if (root.isObject()) {
            objects.add(root);
        } else if (root.isArray()) {
            for (JsonNode element : root) {
                objects.add(element);
            }
        }
        List<Map<String, ?>> rows = new ArrayList<>();
        for (JsonNode object : objects) {
            Map<String, Object> row = row(object);
            if (row == null) {
                log.warn("delivery {}: the transform output is not a flat row set, so there are no DB rows",
                        send.deliveryId());
                return Optional.empty();
            }
            rows.add(row);
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(rows));
    }

    /** One flat row, or null when the node is not an object or a cell is not a scalar. */
    private static Map<String, Object> row(JsonNode object) {
        if (!object.isObject()) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> cell : object.properties()) {
            JsonNode value = cell.getValue();
            Object converted;
            if (value.isNull()) {
                converted = null;
            } else if (value.isString()) {
                converted = value.stringValue();
            } else if (value.isBoolean()) {
                converted = value.booleanValue();
            } else if (value.isIntegralNumber() && value.canConvertToLong()) {
                converted = value.longValue();
            } else if (value.isNumber()) {
                converted = value.decimalValue();
            } else {
                return null;
            }
            row.put(cell.getKey(), converted);
        }
        return row.isEmpty() ? null : row;
    }
}
