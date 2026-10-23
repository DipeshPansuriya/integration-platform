package io.integrationplatform.ingest.internal.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.integrationplatform.config.flow.api.MaskedFields;
import io.integrationplatform.config.rule.api.RuleEvaluation;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.api.RuleViolation;
import io.integrationplatform.ingest.port.IntakeTransaction.RecordResult;
import io.integrationplatform.ingest.port.IntakeTransaction.RecordViolation;
import io.integrationplatform.ingest.validation.ContractSchemaCheck;
import io.integrationplatform.ingest.validation.PayloadValidationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B37 (E11-F1-S1, E11-F1-S3, E6-F2-S1; ADR-011 Decision 4): the intake's validation step per record - the contract
 * schema first, then the interface's VALIDATION rule sets, every violation in one answer, a rejected record never
 * gets a tracking id, and no submitted value is echoed in the answer or in a log line (T-066, BS-11.3).
 */
class IntakeValidationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROBE = "PROBE-9f3c-CARD-4111111111111111";
    private static final UUID RULES = UUID.fromString("a0000000-0000-4000-8000-000000000037");

    private static final String CONTRACT = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
             "required":["customerId","orderId","amount","cardNumber"],
             "properties":{"customerId":{"type":"string"},"orderId":{"type":"string"},
                           "amount":{"type":"number"},
                           "cardNumber":{"type":"string","pattern":"^[0-9]{4}$","maxLength":8},
                           "currency":{"enum":["EUR","USD"]},"channel":{"const":"WEB"},
                           "email":{"type":"string","format":"email"}}}
            """;
    private static final String CLOSED_CONTRACT = """
            {"type":"object","properties":{"orderId":{"type":"string"}},"additionalProperties":false}
            """;

    private final AtomicInteger ruleCalls = new AtomicInteger();
    private List<RuleViolation> ruleViolations = List.of();
    private final RuleEvaluationPort rules = (id, input) -> {
        ruleCalls.incrementAndGet();
        return new RuleEvaluation(id, "VALIDATION", JSON.createObjectNode(), ruleViolations);
    };
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

    private static JsonNode record(String json) {
        return JSON.readTree(json);
    }

    private static final String VALID =
            "{\"customerId\":\"c1\",\"orderId\":\"o1\",\"amount\":10.5,\"cardNumber\":\"1234\"}";

    private RecordResult[] check(String contract, List<UUID> ruleSets, String... records) {
        return validation.check("k-" + contract.hashCode(), () -> record(contract), ruleSets,
                java.util.Arrays.stream(records).map(IntakeValidationTest::record).toList(), "orders");
    }

    @Test
    @DisplayName("a valid record passes: no result (the intake goes on to accept it)")
    void validRecordPasses() {
        assertThat(check(CONTRACT, List.of(), VALID)).containsExactly((RecordResult) null);
    }

    @Test
    @DisplayName("wrong type: amount 'not-a-number' is REJECTED, names amount and SCHEMA_TYPE, has no tracking id")
    void wrongType() {
        RecordResult[] out = check(CONTRACT, List.of(), VALID.replace("10.5", "\"not-a-number\""));
        assertThat(out[0].status()).isEqualTo(RecordResult.REJECTED);
        assertThat(out[0].code()).isEqualTo("PAYLOAD_VALIDATION_FAILED");
        assertThat(out[0].trackingId()).isNull();
        assertThat(out[0].outboxId()).isNull();
        assertThat(out[0].violations()).extracting(RecordViolation::field, RecordViolation::code, RecordViolation::source)
                .containsExactly(tuple("amount", "SCHEMA_TYPE", "SCHEMA"));
    }

    @Test
    @DisplayName("missing required field is named by its own path")
    void missingRequired() {
        RecordResult[] out = check(CONTRACT, List.of(), "{\"customerId\":\"c1\",\"orderId\":\"o1\",\"amount\":1}");
        assertThat(out[0].violations()).extracting(RecordViolation::field, RecordViolation::code)
                .containsExactly(tuple("cardNumber", "SCHEMA_REQUIRED"));
    }

    @Test
    @DisplayName("every violation in ONE answer: ten schema violations come back as ten, never the first only")
    void everyViolationInOneAnswer() {
        RecordResult[] out = check(CONTRACT, List.of(),
                "{\"customerId\":1,\"orderId\":2,\"amount\":\"x\",\"cardNumber\":\"abcdefghijk\","
                        + "\"currency\":\"GBP\",\"channel\":\"POS\",\"email\":5}");
        assertThat(out[0].violations().stream().map(RecordViolation::field).distinct().toList())
                .containsExactlyInAnyOrder("customerId", "orderId", "amount", "cardNumber", "currency", "channel",
                        "email");
        assertThat(out[0].violations()).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("schema first, then rules: both steps' violations in one answer, schema ones first")
    void schemaThenRulesInOneAnswer() {
        ruleViolations = List.of(new RuleViolation("amount", "AMOUNT_TOO_HIGH", "amount must not exceed 100"),
                new RuleViolation("customerId", "CUSTOMER_BLOCKED", "customer is blocked"));
        RecordResult[] out = check(CONTRACT, List.of(RULES),
                "{\"customerId\":\"c1\",\"orderId\":\"o1\",\"amount\":500,\"cardNumber\":\"12\"}");
        assertThat(out[0].violations()).extracting(RecordViolation::source, RecordViolation::field, RecordViolation::code)
                .containsExactly(tuple("SCHEMA", "cardNumber", "SCHEMA_PATTERN"),
                        tuple("RULE", "amount", "AMOUNT_TOO_HIGH"), tuple("RULE", "customerId", "CUSTOMER_BLOCKED"));
        assertThat(ruleCalls).hasValue(1);
    }

    @Test
    @DisplayName("rules alone can reject a record that matches the schema")
    void rulesAloneReject() {
        ruleViolations = List.of(new RuleViolation("amount", "AMOUNT_TOO_HIGH", "amount must not exceed 100"));
        RecordResult[] out = check(CONTRACT, List.of(RULES), VALID);
        assertThat(out[0].violations()).extracting(RecordViolation::source, RecordViolation::code)
                .containsExactly(tuple("RULE", "AMOUNT_TOO_HIGH"));
    }

    @Test
    @Tag("negative")
    @DisplayName("a payload that is not a JSON object is rejected on the schema and the rules never run")
    void rulesDoNotRunOnAnArray() {
        RecordResult[] out = check(CONTRACT, List.of(RULES), "[1,2]");
        assertThat(out[0].status()).isEqualTo(RecordResult.REJECTED);
        assertThat(out[0].violations()).isNotEmpty().allSatisfy(v -> assertThat(v.source()).isEqualTo("SCHEMA"));
        assertThat(ruleCalls).hasValue(0);
    }

    @Test
    @DisplayName("unknown field follows the contract's policy: closed contract rejects it, open contract accepts it")
    void unknownFieldFollowsTheContract() {
        RecordResult[] closed = check(CLOSED_CONTRACT, List.of(), "{\"orderId\":\"o1\",\"surprise\":1}");
        assertThat(closed[0].violations()).extracting(RecordViolation::code).containsExactly("SCHEMA_ADDITIONAL_PROPERTIES");
        assertThat(closed[0].violations().get(0).field()).isEqualTo("surprise");
        assertThat(check(CONTRACT, List.of(), VALID.replace("}", ",\"surprise\":1}"))).containsExactly((RecordResult) null);
        assertThat(check("{}", List.of(), "{\"anything\":[1,2,3]}")).containsExactly((RecordResult) null);
    }

    @Test
    @DisplayName("bulk mix: results line up by index, only the invalid ones are rejected, the contract is read once")
    void bulkMix() {
        AtomicInteger reads = new AtomicInteger();
        List<JsonNode> records = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            records.add(record(i % 14 == 3 ? VALID.replace("10.5", "\"bad\"") : VALID));
        }
        RecordResult[] out = validation.check("bulk-key", () -> {
            reads.incrementAndGet();
            return record(CONTRACT);
        }, List.of(), records, "orders");
        long rejected = java.util.Arrays.stream(out).filter(r -> r != null).count();
        assertThat(rejected).isEqualTo(7);
        for (int i = 0; i < 100; i++) {
            assertThat(out[i] == null).as("record %d", i).isEqualTo(i % 14 != 3);
            if (out[i] != null) {
                assertThat(out[i].index()).isEqualTo(i);
            }
        }
        assertThat(reads).as("contract schema reads for the whole bulk").hasValue(1);
    }

    @Test
    @DisplayName("no submitted value in the answer or the log: type, pattern, maxLength, enum, const, format")
    void noValueEchoedAnywhere() {
        MaskedFields.register(java.util.Set.of(PROBE));
        String body = "{\"customerId\":\"" + PROBE + "\",\"orderId\":\"" + PROBE + "\",\"amount\":\"" + PROBE
                + "\",\"cardNumber\":\"" + PROBE + "\",\"currency\":\"" + PROBE + "\",\"channel\":\"" + PROBE
                + "\",\"email\":\"" + PROBE + "\"}";
        ruleViolations = List.of(new RuleViolation("amount", "AMOUNT_BAD", "amount is bad"));
        RecordResult[] out = check(CONTRACT, List.of(RULES), body);

        assertThat(out[0].violations()).hasSizeGreaterThanOrEqualTo(4);
        String answer = JSON.writeValueAsString(out[0]);
        assertThat(answer).as("the rejection names fields and codes").contains("amount").contains("SCHEMA_");
        assertThat(answer).as("the answer never carries the submitted value").doesNotContain(PROBE)
                .doesNotContain("4111111111111111");
        assertThat(logs.list).as("the step logs its summary").isNotEmpty();
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList()).allSatisfy(
                line -> assertThat(line).doesNotContain(PROBE).doesNotContain("4111111111111111"));
    }

    @Test
    @DisplayName("a rule message that carries a registered masked value is scrubbed before it leaves the intake")
    void maskedValueInARuleMessageIsScrubbed() {
        MaskedFields.register(java.util.Set.of(PROBE));
        ruleViolations = List.of(new RuleViolation("cardNumber", "CARD_BLOCKED", "card " + PROBE + " is blocked"));
        RecordResult[] out = check(CONTRACT, List.of(RULES), VALID);
        assertThat(out[0].violations().get(0).message()).doesNotContain(PROBE).contains(MaskedFields.MARKER);
    }

    @Test
    @DisplayName("every code the intake step can put on a rejection is a known refusal reason")
    void rejectionCodeIsAKnownReason() {
        RecordResult[] out = check(CONTRACT, List.of(), "{}");
        assertThat(IntakeService.refusal(out[0]).reason().name()).isEqualTo(out[0].code());
        assertThat(IntakeService.refusal(out[0]).violations()).isEqualTo(out[0].violations());
    }
}
