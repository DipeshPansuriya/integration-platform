package io.integrationplatform.config.mapping.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one canonical mapping content (ADR-013 Decision 2): a mapping version is deduplicated by the SHA-256 of
 * its content - engine, the two bound contract versions, the field mappings and the compiled expression - so
 * an unchanged mapping keeps its version row and a mapping returning to earlier content reuses that row.
 *
 * <p>The field mappings are canonicalized once, at save: object keys sorted, arrays in order. Both the live
 * {@code mapping} row and every {@code mapping_version} row store exactly this canonical text, which is what
 * makes a save with reordered keys the SAME content (and the pin check's snapshot comparison order-safe for
 * objects, order-honest for arrays).</p>
 */
public final class MappingContent {

    /** The engine of Release 1 (ADR-006 Accepted: JSONata via Dashjoin). The table CHECK carries the set. */
    public static final String ENGINE = "JSONATA";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MappingContent() {
    }

    /** The canonical text of a mapping's field-mappings JSON (object keys sorted, arrays in order). */
    public static String canonicalFieldMappings(String fieldMappingsJson) {
        return canonicalize(JSON.readTree(fieldMappingsJson == null || fieldMappingsJson.isBlank()
                ? "[]" : fieldMappingsJson)).toString();
    }

    /** The content hash of one mapping state; 32 bytes (the column CHECK in V2_2_730). */
    public static byte[] contentSha256(String engine, String sourceContractVersionId,
            String targetContractVersionId, String fieldMappingsJson, String compiledExpression) {
        String content = engine + '\n' + sourceContractVersionId + '\n' + targetContractVersionId + '\n'
                + canonicalFieldMappings(fieldMappingsJson) + '\n' + compiledExpression;
        try {
            return MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JVM", e);
        }
    }

    /** The hex of a content hash, for test fixtures that seed a version row by hand. */
    public static String contentSha256Hex(String engine, String sourceContractVersionId,
            String targetContractVersionId, String fieldMappingsJson, String compiledExpression) {
        return hex(contentSha256(engine, sourceContractVersionId, targetContractVersionId, fieldMappingsJson,
                compiledExpression));
    }

    /** The field every link targets: the root segment of each link's {@code target}. */
    public static java.util.List<String> targetRoots(String fieldMappingsJson) {
        JsonNode mappings = JSON.readTree(fieldMappingsJson == null || fieldMappingsJson.isBlank()
                ? "[]" : fieldMappingsJson);
        java.util.List<String> roots = new java.util.ArrayList<>();
        for (JsonNode link : mappings) {
            String target = link.path("target").asString("");
            if (!target.isEmpty()) {
                roots.add(target.split("\\.", 2)[0]);
            }
        }
        return roots;
    }

    /** Recursively sorts every object's keys; scalars and arrays keep their order. */
    public static JsonNode canonicalize(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            new TreeMap<>(Map.copyOf(sortableProperties(object))).forEach((key, value) ->
                    sorted.set(key, canonicalize(value)));
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            array.forEach(entry -> copy.add(canonicalize(entry)));
            return copy;
        }
        return node;
    }

    private static Map<String, JsonNode> sortableProperties(ObjectNode object) {
        Map<String, JsonNode> properties = new TreeMap<>();
        object.propertyNames().forEach(name -> properties.put(name, object.get(name)));
        return properties;
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}