package io.integrationplatform.config.contract.application;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * T-105 (ADR-011 Decision 2): turns an imported contract document into the one canonical JSON Schema (draft 2020-12)
 * a {@code contract_version} row stores. An OpenAPI 3.x document is parsed by swagger-parser-v3 2.1.48 and one of its
 * component schemas becomes the version's schema (references between components move under {@code $defs}, so the
 * stored schema stands alone; the original document is kept beside it). A JSON Schema is checked against the draft
 * 2020-12 meta-schema by networknt json-schema-validator 2.0.1 (R12) and stored as given.
 *
 * <p>A document that does not parse is refused with the parser's own errors ({@link ContractImportException}). A
 * reference outside the document is refused, and nothing is ever fetched: the parser runs without resolution and the
 * validator's loader has remote fetching off (the meta-schema comes from the validator's own classpath copy).
 * Stateless; the caller stores nothing when this throws.
 */
public final class ContractDocumentImporter {

    /** The input formats the import accepts. */
    public enum Format {
        OPENAPI,
        JSON_SCHEMA
    }

    /** The canonical schema to store, and the original OpenAPI document ({@code null} for a JSON Schema import). */
    public record ImportedSchema(JsonNode jsonSchema, JsonNode openapiDocument) {
    }

    static final String DRAFT_2020_12 = SpecificationVersion.DRAFT_2020_12.getDialectId();
    private static final String COMPONENT_REF = "#/components/schemas/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Schema metaSchema;

    public ContractDocumentImporter() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));
        this.metaSchema = registry.getSchema(SchemaLocation.of(DRAFT_2020_12));
    }

    /**
     * @param document   the document as a JSON value, or as text (an OpenAPI document may be YAML text)
     * @param schemaName the OpenAPI component schema to import; may be {@code null} when the document has exactly one
     */
    public ImportedSchema importDocument(Format format, JsonNode document, String schemaName) {
        if (document == null || document.isMissingNode() || document.isNull()) {
            throw ContractImportException.refused("document is required", List.of());
        }
        return switch (format) {
            case JSON_SCHEMA -> new ImportedSchema(validJsonSchema(asObject(document)), null);
            case OPENAPI -> importOpenApi(document, schemaName);
        };
    }

    private ImportedSchema importOpenApi(JsonNode document, String schemaName) {
        String text = document.isString() ? document.asString() : document.toString();
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(text, null, null);
        List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();
        OpenAPI openApi = result.getOpenAPI();
        if (openApi == null || !messages.isEmpty()) {
            throw ContractImportException.refused("the OpenAPI document does not parse", messages);
        }
        String serialized = openApi.getSpecVersion() == SpecVersion.V31 ? Json31.pretty(openApi) : Json.pretty(openApi);
        JsonNode parsed = JSON.readTree(serialized);
        JsonNode original = document.isObject() ? document : parsed;
        refuseExternalReferences(original);

        JsonNode components = parsed.path("components").path("schemas");
        List<String> names = new ArrayList<>(components.propertyNames());
        String chosen = schemaName;
        if (chosen == null || chosen.isBlank()) {
            if (names.size() != 1) {
                throw ContractImportException.refused("name the component schema to import in schemaName; the"
                        + " document has " + (names.isEmpty() ? "none" : String.join(", ", names)), List.of());
            }
            chosen = names.get(0);
        } else if (!components.has(chosen)) {
            throw ContractImportException.refused("schemaName " + chosen + " is not a component schema of the"
                    + " document; it has " + (names.isEmpty() ? "none" : String.join(", ", names)), List.of());
        }

        ObjectNode schema = JSON.createObjectNode();
        schema.put("$schema", DRAFT_2020_12);
        JsonNode root = moveReferences(components.get(chosen));
        root.properties().forEach(field -> {
            if (!field.getKey().equals("$schema")) {
                schema.set(field.getKey(), field.getValue());
            }
        });
        if (components.toString().contains(COMPONENT_REF)) {
            ObjectNode defs = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> component : components.properties()) {
                defs.set(component.getKey(), moveReferences(component.getValue()));
            }
            schema.set("$defs", defs);
        }
        return new ImportedSchema(validJsonSchema(schema), original);
    }

    private JsonNode validJsonSchema(JsonNode schema) {
        refuseExternalReferences(schema);
        List<String> errors = new ArrayList<>();
        metaSchema.validate(schema.toString(), InputFormat.JSON).forEach(error -> errors.add(error.toString()));
        if (!errors.isEmpty()) {
            throw ContractImportException.refused("the JSON Schema is not valid draft 2020-12", errors);
        }
        return schema;
    }

    private static JsonNode asObject(JsonNode document) {
        JsonNode tree = document;
        if (document.isString()) {
            try {
                tree = JSON.readTree(document.asString());
            } catch (JacksonException unparsable) {
                throw ContractImportException.refused("the JSON Schema does not parse as JSON",
                        List.of(unparsable.getOriginalMessage()));
            }
        }
        if (tree == null || !tree.isObject()) {
            throw ContractImportException.refused("a JSON Schema document must be a JSON object", List.of());
        }
        return tree;
    }

    /** Every {@code $ref} must point inside the document ({@code #...}); nothing outside it is ever fetched. */
    private static void refuseExternalReferences(JsonNode document) {
        List<String> external = new ArrayList<>();
        collectExternalReferences(document, external);
        if (!external.isEmpty()) {
            throw ContractImportException.refused("references outside the document are not allowed",
                    external.stream().map(ref -> "external $ref " + ref).toList());
        }
    }

    private static void collectExternalReferences(JsonNode node, List<String> external) {
        if (node.isObject()) {
            node.properties().forEach(field -> {
                if (field.getKey().equals("$ref") && field.getValue().isString()
                        && !field.getValue().asString().startsWith("#")) {
                    external.add(field.getValue().asString());
                } else {
                    collectExternalReferences(field.getValue(), external);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectExternalReferences(child, external));
        }
    }

    /** A copy with every {@code #/components/schemas/X} reference rewritten to {@code #/$defs/X}. */
    private static JsonNode moveReferences(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = JSON.createObjectNode();
            node.properties().forEach(field -> {
                JsonNode value = field.getValue();
                if (field.getKey().equals("$ref") && value.isString() && value.asString().startsWith(COMPONENT_REF)) {
                    copy.set("$ref", StringNode.valueOf("#/$defs/" + value.asString().substring(COMPONENT_REF.length())));
                } else {
                    copy.set(field.getKey(), moveReferences(value));
                }
            });
            return copy;
        }
        if (node.isArray()) {
            var copy = JSON.createArrayNode();
            node.forEach(child -> copy.add(moveReferences(child)));
            return copy;
        }
        return node;
    }
}
