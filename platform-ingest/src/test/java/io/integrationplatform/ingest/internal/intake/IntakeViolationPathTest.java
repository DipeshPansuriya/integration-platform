package io.integrationplatform.ingest.internal.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.integrationplatform.config.rule.api.RuleEvaluation;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.ingest.adapter.in.IntakeController;
import io.integrationplatform.ingest.port.AcceptedMessage;
import io.integrationplatform.ingest.port.IntakeTransaction;
import io.integrationplatform.ingest.port.IntakeTransaction.RecordResult;
import io.integrationplatform.ingest.port.IntakeTransaction.RecordViolation;
import io.integrationplatform.ingest.validation.ContractSchemaCheck;
import io.integrationplatform.ingest.validation.PayloadValidationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Row 372 lane L8: a submitted property NAME is submitted data too. A key that is not declared in the contract
 * schema (additionalProperties, unevaluatedProperties, patternProperties, propertyNames) never reaches the 422 body
 * or the INFO log: the violation reports the PARENT path (declared names only) and the stable code.
 */
class IntakeViolationPathTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MARKER = "SECRET-KEY-NAME-PROBE";
    private static final String CLOSED = """
            {"type":"object","required":["orderId"],
             "properties":{"orderId":{"type":"string"},
                           "address":{"type":"object","properties":{"city":{"type":"string"}},
                                      "additionalProperties":false},
                           "lines":{"type":"array","items":{"type":"object",
                                    "properties":{"sku":{"type":"string"}},"additionalProperties":false}}},
             "additionalProperties":false}
            """;
    private static final String TYPED_EXTRAS = """
            {"type":"object","properties":{"orderId":{"type":"string"}},
             "additionalProperties":{"type":"string"}}
            """;
    private static final String UNEVALUATED = """
            {"type":"object","properties":{"orderId":{"type":"string"}},"unevaluatedProperties":false}
            """;
    private static final String PATTERNED = """
            {"type":"object","properties":{"orderId":{"type":"string"}},
             "patternProperties":{"^x-":{"type":"string"}},"additionalProperties":false}
            """;
    private static final String NAMED = """
            {"type":"object","properties":{"orderId":{"type":"string"}},"propertyNames":{"pattern":"^[a-z]+$"}}
            """;

    private final RuleEvaluationPort rules = (id, input) -> new RuleEvaluation(id, "VALIDATION",
            JSON.createObjectNode(), List.of());
    private final IntakeValidation validation = new IntakeValidation(
            new PayloadValidationService(new ContractSchemaCheck(), rules));
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(IntakeValidation.class);
        logger.setLevel(Level.TRACE);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private RecordResult[] check(String contract, String... records) {
        return validation.check("k-" + contract.hashCode(), () -> JSON.readTree(contract), List.of(),
                java.util.Arrays.stream(records).map(JSON::readTree).toList(), "orders");
    }

    /** What the HTTP client reads: the controller's real body for a single refusal and for a bulk answer. */
    private List<String> bodies(String contract, String record) throws Exception {
        RecordResult[] out = check(contract, record, "{\"orderId\":\"ok\"}");
        IntakeTransaction fake = new IntakeTransaction() {
            @Override
            public AcceptedMessage accept(IntakeRequest request) {
                throw IntakeService.refusal(out[0]);
            }

            @Override
            public List<RecordResult> acceptBulk(BulkRequest request) {
                return List.of(out[0], RecordResult.accepted(1, UUID.randomUUID(), 1));
            }
        };
        MockMvc http = MockMvcBuilders.standaloneSetup(new IntakeController(fake)).build();
        String single = http.perform(post("/api/v1/interfaces/orders/messages")
                .header("X-Source-Contract-Version", "1.0").header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON).content(record))
                .andReturn().getResponse().getContentAsString();
        String bulk = http.perform(post("/api/v1/interfaces/orders/messages:bulk")
                .header("X-Source-Contract-Version", "1.0").header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON)
                .content("[" + record + ",{\"orderId\":\"ok\"}]"))
                .andReturn().getResponse().getContentAsString();
        return List.of(single, bulk);
    }

    private void assertNoMarker(String contract, String record) throws Exception {
        for (String body : bodies(contract, record)) {
            assertThat(body).as("response body").contains("SCHEMA_").doesNotContain(MARKER);
        }
        assertThat(logs.list).as("the step logs its summary").isNotEmpty();
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .allSatisfy(line -> assertThat(line).doesNotContain(MARKER));
    }

    @Test
    @DisplayName("unknown key under additionalProperties:false: parent path and code, never the key (single, bulk, log)")
    void unknownKeyAtTheRoot() throws Exception {
        String record = "{\"orderId\":\"o1\",\"" + MARKER + "\":1}";
        assertNoMarker(CLOSED, record);
        RecordViolation only = check(CLOSED, record)[0].violations().get(0);
        assertThat(only.code()).isEqualTo("SCHEMA_ADDITIONAL_PROPERTIES");
        assertThat(only.field()).isEqualTo("$");
    }

    @Test
    @DisplayName("unknown key in a nested object and in an array item: the declared parent path survives, the key does not")
    void unknownKeyNested() throws Exception {
        String nested = "{\"orderId\":\"o1\",\"address\":{\"city\":\"x\",\"" + MARKER + "\":1}}";
        assertNoMarker(CLOSED, nested);
        RecordViolation v = check(CLOSED, nested)[0].violations().get(0);
        assertThat(v.code()).isEqualTo("SCHEMA_ADDITIONAL_PROPERTIES");
        assertThat(v.field()).isEqualTo("address");
        String inArray = "{\"orderId\":\"o1\",\"lines\":[{\"sku\":\"a\"},{\"sku\":\"b\",\"" + MARKER + "\":1}]}";
        assertNoMarker(CLOSED, inArray);
        assertThat(check(CLOSED, inArray)[0].violations().get(0).field()).isEqualTo("lines[1]");
    }

    @Test
    @DisplayName("a declared name still appears: wrong type on a declared field and a missing required field")
    void declaredNamesStay() {
        RecordViolation type = check(CLOSED, "{\"orderId\":5}")[0].violations().get(0);
        assertThat(type.field()).isEqualTo("orderId");
        RecordViolation required = check(CLOSED, "{}")[0].violations().get(0);
        assertThat(required.field()).isEqualTo("orderId");
        assertThat(required.code()).isEqualTo("SCHEMA_REQUIRED");
    }

    @Test
    @DisplayName("an undeclared key whose VALUE breaks the additionalProperties schema: the path stops at the parent")
    void undeclaredKeyWithBadValue() throws Exception {
        String record = "{\"orderId\":\"o1\",\"" + MARKER + "\":5}";
        assertNoMarker(TYPED_EXTRAS, record);
        assertThat(check(TYPED_EXTRAS, record)[0].violations().get(0).field()).isEqualTo("$");
    }

    @Test
    @DisplayName("unevaluatedProperties, patternProperties and propertyNames violations never carry the key")
    void otherKeywords() throws Exception {
        assertNoMarker(UNEVALUATED, "{\"orderId\":\"o1\",\"" + MARKER + "\":1}");
        assertNoMarker(PATTERNED, "{\"orderId\":\"o1\",\"" + MARKER + "\":1}");
        assertNoMarker(PATTERNED, "{\"orderId\":\"o1\",\"x-" + MARKER + "\":1}");
        assertNoMarker(NAMED, "{\"orderId\":\"o1\",\"" + MARKER + "\":1}");
    }

    @Test
    @DisplayName("a declared name behind a $ref keeps its path; two unknown keys under one parent give one violation")
    void declaredViaRefAndTwoUnknownKeys() {
        String viaRef = """
                {"type":"object","properties":{"address":{"$ref":"#/$defs/addr"}},
                 "$defs":{"addr":{"type":"object","properties":{"city":{"type":"string"}},
                                  "additionalProperties":false}}}
                """;
        assertThat(check(viaRef, "{\"address\":{\"city\":5}}")[0].violations().get(0).field())
                .isEqualTo("address.city");
        List<RecordViolation> two = check(viaRef, "{\"address\":{\"" + MARKER + "\":1,\"" + MARKER + "-2\":2}}")[0]
                .violations();
        assertThat(two).hasSize(1);
        assertThat(two.get(0).field()).isEqualTo("address");
    }
}
