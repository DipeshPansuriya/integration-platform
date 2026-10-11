package io.integrationplatform.config.e5.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.integrationplatform.config.contract.application.ContractDocumentImporter;
import io.integrationplatform.config.contract.application.ContractDocumentImporter.Format;
import io.integrationplatform.config.contract.application.ContractDocumentImporter.ImportedSchema;
import io.integrationplatform.config.contract.application.ContractImportException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

/**
 * E5-F2-S1 (T-105), the import half of "Two contract versions side by side" and "@negative An invalid schema is
 * refused": a target contract arrives as an OpenAPI 3.x document (parsed by swagger-parser-v3 2.1.48) or as a JSON
 * Schema (checked against the draft 2020-12 meta-schema by networknt json-schema-validator 2.0.1, R12) and leaves as
 * the one canonical JSON Schema the {@code contract_version} row stores (ADR-011 Decision 2). A document that does not
 * parse is refused with the parser's own errors; the service stores nothing in that case. Bare test: no container.
 */
class ContractDocumentImporterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    private final ContractDocumentImporter importer = new ContractDocumentImporter();

    /** Target B's contract v2.0 as an OpenAPI 3.0 document: {clientId, referenceNo, invoiceValue}. */
    static final String TARGET_B_OPENAPI = """
            {"openapi":"3.0.3","info":{"title":"Target B orders","version":"2.0"},
             "paths":{"/orders":{"post":{"requestBody":{"content":{"application/json":{"schema":
                 {"$ref":"#/components/schemas/TargetBOrder"}}}},
                 "responses":{"200":{"description":"accepted"}}}}},
             "components":{"schemas":{"TargetBOrder":{"type":"object",
                 "required":["clientId","referenceNo","invoiceValue"],
                 "properties":{"clientId":{"type":"string"},"referenceNo":{"type":"string"},
                     "invoiceValue":{"type":"number"}}}}}}""";

    /** Target A's contract v1.0 as a JSON Schema: {customer_code, order_number, total_amount}. */
    static final String TARGET_A_JSON_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
             "required":["customer_code","order_number","total_amount"],
             "properties":{"customer_code":{"type":"string"},"order_number":{"type":"string"},
                 "total_amount":{"type":"number"}}}""";

    @Test
    @DisplayName("An OpenAPI 3.x component schema becomes the version's draft 2020-12 JSON Schema; the document is kept")
    void openApiComponentSchemaBecomesTheVersionsJsonSchema() {
        JsonNode document = JSON.readTree(TARGET_B_OPENAPI);

        ImportedSchema imported = importer.importDocument(Format.OPENAPI, document, null);

        assertThat(imported.jsonSchema().path("$schema").asString()).isEqualTo(DRAFT_2020_12);
        assertThat(imported.jsonSchema().path("properties").propertyNames())
                .containsExactlyInAnyOrder("clientId", "referenceNo", "invoiceValue");
        assertThat(imported.openapiDocument()).as("the original OpenAPI document is stored beside it")
                .isEqualTo(document);
    }

    @Test
    @DisplayName("An OpenAPI document given as YAML text is parsed the same way")
    void openApiAsYamlTextIsAccepted() {
        String yaml = """
                openapi: 3.0.3
                info: {title: Target B orders, version: '2.0'}
                paths: {}
                components:
                  schemas:
                    TargetBOrder:
                      type: object
                      properties:
                        clientId: {type: string}
                        referenceNo: {type: string}
                        invoiceValue: {type: number}
                """;

        ImportedSchema imported = importer.importDocument(Format.OPENAPI, StringNode.valueOf(yaml), "TargetBOrder");

        assertThat(imported.jsonSchema().path("properties").propertyNames())
                .containsExactlyInAnyOrder("clientId", "referenceNo", "invoiceValue");
        assertThat(imported.openapiDocument().path("openapi").asString()).isEqualTo("3.0.3");
    }

    @Test
    @DisplayName("References between component schemas move under $defs, so the stored schema stands alone")
    void referencesBetweenComponentsMoveUnderDefs() {
        JsonNode document = JSON.readTree("""
                {"openapi":"3.0.3","info":{"title":"t","version":"1"},"paths":{},
                 "components":{"schemas":{
                   "Order":{"type":"object","properties":{"lines":{"type":"array",
                       "items":{"$ref":"#/components/schemas/Line"}}}},
                   "Line":{"type":"object","properties":{"sku":{"type":"string"}}}}}}""");

        ImportedSchema imported = importer.importDocument(Format.OPENAPI, document, "Order");

        assertThat(imported.jsonSchema().at("/properties/lines/items/$ref").asString()).isEqualTo("#/$defs/Line");
        assertThat(imported.jsonSchema().at("/$defs/Line/properties/sku/type").asString()).isEqualTo("string");
    }

    @Test
    @DisplayName("A draft 2020-12 JSON Schema is stored as given")
    void jsonSchemaIsStoredAsGiven() {
        JsonNode document = JSON.readTree(TARGET_A_JSON_SCHEMA);

        ImportedSchema imported = importer.importDocument(Format.JSON_SCHEMA, document, null);

        assertThat(imported.jsonSchema()).isEqualTo(document);
        assertThat(imported.openapiDocument()).isNull();
    }

    @Test
    @Tag("negative")
    @DisplayName("@negative An OpenAPI document that does not parse is refused with the parser's errors")
    void anInvalidOpenApiDocumentIsRefusedWithTheParsersErrors() {
        JsonNode noInfo = JSON.readTree("{\"openapi\":\"3.0.3\",\"paths\":{}}");

        ContractImportException refused = catchThrowableOfType(ContractImportException.class,
                () -> importer.importDocument(Format.OPENAPI, noInfo, null));

        assertThat(refused).as("the import is refused").isNotNull();
        assertThat(refused.httpStatus()).isEqualTo(400);
        assertThat(parserErrors(refused)).isNotEmpty().anySatisfy(error -> assertThat(error).contains("info"));
    }

    @Test
    @Tag("negative")
    @DisplayName("@negative A JSON Schema that is not valid draft 2020-12 is refused with the validator's errors")
    void anInvalidJsonSchemaIsRefusedWithTheValidatorsErrors() {
        JsonNode badTypes = JSON.readTree("{\"type\":12,\"properties\":[]}");

        ContractImportException refused = catchThrowableOfType(ContractImportException.class,
                () -> importer.importDocument(Format.JSON_SCHEMA, badTypes, null));

        assertThat(refused).as("the import is refused").isNotNull();
        assertThat(parserErrors(refused)).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @Tag("negative")
    @DisplayName("@negative Text that is neither JSON nor YAML is refused with the parse error")
    void unparseableTextIsRefused() {
        ContractImportException refused = catchThrowableOfType(ContractImportException.class,
                () -> importer.importDocument(Format.JSON_SCHEMA, StringNode.valueOf("{ not: [json"), null));

        assertThat(refused).as("the import is refused").isNotNull();
        assertThat(parserErrors(refused)).isNotEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("@negative A reference outside the document is refused (nothing is ever fetched)")
    void anExternalReferenceIsRefused() {
        JsonNode external = JSON.readTree("{\"type\":\"object\",\"properties\":{\"a\":"
                + "{\"$ref\":\"https://schemas.invalid/other.json\"}}}");

        ContractImportException refused = catchThrowableOfType(ContractImportException.class,
                () -> importer.importDocument(Format.JSON_SCHEMA, external, null));

        assertThat(refused).as("the import is refused").isNotNull();
        assertThat(parserErrors(refused)).anySatisfy(error -> assertThat(error).contains("https://schemas.invalid"));
    }

    @Test
    @Tag("negative")
    @DisplayName("@negative Two component schemas and no schemaName: refused, naming the choices")
    void anAmbiguousOpenApiDocumentNamesTheChoices() {
        JsonNode two = JSON.readTree("""
                {"openapi":"3.0.3","info":{"title":"t","version":"1"},"paths":{},
                 "components":{"schemas":{"A":{"type":"object"},"B":{"type":"object"}}}}""");

        ContractImportException refused = catchThrowableOfType(ContractImportException.class,
                () -> importer.importDocument(Format.OPENAPI, two, null));

        assertThat(refused).as("the import is refused").isNotNull();
        assertThat(refused.body().get("error").toString()).contains("schemaName", "A", "B");
    }

    @SuppressWarnings("unchecked")
    private static List<String> parserErrors(ContractImportException refused) {
        Object errors = refused.body().get("parserErrors");
        assertThat(errors).as("the refusal body carries parserErrors").isInstanceOf(List.class);
        return (List<String>) errors;
    }
}
