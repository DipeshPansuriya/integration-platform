package io.integrationplatform.ingest.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.integrationplatform.ingest.port.AcceptedMessage;
import io.integrationplatform.ingest.port.IntakeRefusedException;
import io.integrationplatform.ingest.port.IntakeRefusedException.Reason;
import io.integrationplatform.ingest.port.IntakeTransaction;
import io.integrationplatform.ingest.port.IntakeTransaction.RecordResult;
import io.integrationplatform.ingest.port.IntakeTransaction.RecordViolation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Row 372 lane L8: the intake answers are built from maps, so springdoc sees a bare object for every status and the
 * generated UI client cannot type {@code violations}. The declared schema (documentation only) must point at the
 * named DTOs, and the DTO field names must equal the keys the controller really emits - a drift fails here.
 */
class IntakeResponseDocumentationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<RecordViolation> VIOLATIONS = List.of(
            new RecordViolation("amount", "SCHEMA_TYPE", "string found, number expected", "SCHEMA"),
            new RecordViolation("amount", "AMOUNT_TOO_HIGH", "amount must not exceed 100", "RULE"));

    private static Set<String> fields(Class<?> dto) {
        return Arrays.stream(dto.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static Optional<ApiResponse> declared(String method, String status) {
        return Arrays.stream(IntakeController.class.getDeclaredMethods()).filter(m -> m.getName().equals(method))
                .flatMap(m -> Arrays.stream(m.getAnnotationsByType(ApiResponse.class)))
                .filter(r -> r.responseCode().equals(status)).findFirst();
    }

    private static Class<?> schemaOf(String method, String status) {
        return declared(method, status).map(r -> r.content()[0].schema().implementation()).orElseThrow();
    }

    @Test
    @DisplayName("422 and 202 declare the named DTOs, not a bare object")
    void declaredSchemasPointAtTheDtos() {
        assertThat(schemaOf("submit", "422")).isEqualTo(IntakeResponses.IntakeErrorBody.class);
        assertThat(schemaOf("submitBulk", "422")).isEqualTo(IntakeResponses.BulkIntakeRejection.class);
        assertThat(schemaOf("submitBulk", "202")).isEqualTo(IntakeResponses.BulkIntakeResponse.class);
    }

    @Test
    @DisplayName("DTO field names equal the keys of the real bodies: single 422, bulk 202 and bulk 422")
    void dtoFieldsEqualTheEmittedKeys() throws Exception {
        List<RecordResult> mixed = List.of(
                RecordResult.rejected(0, "PAYLOAD_VALIDATION_FAILED", "2 violation(s)", VIOLATIONS),
                RecordResult.accepted(1, UUID.randomUUID(), 1));
        List<RecordResult> allRejected = List.of(
                RecordResult.rejected(0, "PAYLOAD_VALIDATION_FAILED", "2 violation(s)", VIOLATIONS));
        String single = answer(true, null, "/api/v1/interfaces/orders/messages", "{\"a\":1}");
        String bulk202 = answer(false, mixed, "/api/v1/interfaces/orders/messages:bulk", "[{\"a\":1},{\"a\":2}]");
        String bulk422 = answer(false, allRejected, "/api/v1/interfaces/orders/messages:bulk", "[{\"a\":1}]");

        JsonNode s = JSON.readTree(single);
        assertThat(keys(s)).isEqualTo(fields(IntakeResponses.IntakeErrorBody.class));
        assertThat(keys(s.get("violations").get(0))).isEqualTo(fields(IntakeResponses.Violation.class));

        JsonNode ok = JSON.readTree(bulk202);
        assertThat(keys(ok)).isEqualTo(fields(IntakeResponses.BulkIntakeResponse.class));
        Set<String> seen = new LinkedHashSet<>();
        ok.get("results").forEach(r -> seen.addAll(keys(r)));
        assertThat(seen).isEqualTo(fields(IntakeResponses.BulkIntakeResult.class));
        assertThat(keys(ok.get("results").get(0).get("violations").get(0)))
                .isEqualTo(fields(IntakeResponses.Violation.class));

        assertThat(keys(JSON.readTree(bulk422))).isEqualTo(fields(IntakeResponses.BulkIntakeRejection.class));
    }

    @Test
    @DisplayName("the violation source enum is exactly the two steps the validation reports")
    void sourceEnumMatchesTheValidationSteps() {
        assertThat(Arrays.stream(IntakeResponses.Violation.Source.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(io.integrationplatform.ingest.validation.Violation.Source.values())
                                .map(Enum::name).toList());
    }

    private static String answer(boolean refuse, List<RecordResult> results, String path, String body)
            throws Exception {
        IntakeTransaction fake = new IntakeTransaction() {
            @Override
            public AcceptedMessage accept(IntakeRequest request) {
                throw new IntakeRefusedException(Reason.PAYLOAD_VALIDATION_FAILED, "2 violation(s)", VIOLATIONS);
            }

            @Override
            public List<RecordResult> acceptBulk(BulkRequest request) {
                return results;
            }
        };
        MockMvc http = MockMvcBuilders.standaloneSetup(new IntakeController(fake)).build();
        return http.perform(post(path).header("X-Source-Contract-Version", "1.0").header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse()
                .getContentAsString();
    }
}
