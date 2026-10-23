package io.integrationplatform.ingest.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.integrationplatform.ingest.port.AcceptedMessage;
import io.integrationplatform.ingest.port.IntakeRefusedException;
import io.integrationplatform.ingest.port.IntakeRefusedException.Reason;
import io.integrationplatform.ingest.port.IntakeTransaction;
import io.integrationplatform.ingest.port.IntakeTransaction.RecordResult;
import io.integrationplatform.ingest.port.IntakeTransaction.RecordViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * B37 (ADR-011 Decision 4 response table): the HTTP shape of a contract/rule rejection - single record 422 with every
 * violation, bulk per-record REJECTED with every violation (202 when one record was accepted, 422 when none), and
 * the reason mapping covers EVERY intake refusal reason (a missing mapping used to surface as the 503 catch-all).
 */
class IntakeValidationEndpointTest {

    private static final String SINGLE = "/api/v1/interfaces/orders/messages";
    private static final String BULK = "/api/v1/interfaces/orders/messages:bulk";
    private static final List<RecordViolation> VIOLATIONS = List.of(
            new RecordViolation("amount", "SCHEMA_TYPE", "string found, number expected", "SCHEMA"),
            new RecordViolation("customerId", "SCHEMA_REQUIRED", "required property is missing", "SCHEMA"),
            new RecordViolation("amount", "AMOUNT_TOO_HIGH", "amount must not exceed 100", "RULE"));

    private IntakeTransaction intake = new Fake(List.of());
    private MockMvc http() {
        return MockMvcBuilders.standaloneSetup(new IntakeController(intake)).build();
    }

    private static MockHttpServletRequestBuilder call(String path, String body) {
        return post(path).header("X-Source-Contract-Version", "1.0").header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    @DisplayName("single record that breaks the contract: 422 PAYLOAD_VALIDATION_FAILED with every violation, no tracking id")
    void singleRejectionIs422WithEveryViolation() throws Exception {
        intake = new Fake(new IntakeRefusedException(Reason.PAYLOAD_VALIDATION_FAILED, "3 violation(s)", VIOLATIONS));
        http().perform(call(SINGLE, "{\"amount\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYLOAD_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.trackingId").doesNotExist())
                .andExpect(jsonPath("$.violations.length()").value(3))
                .andExpect(jsonPath("$.violations[0].field").value("amount"))
                .andExpect(jsonPath("$.violations[0].code").value("SCHEMA_TYPE"))
                .andExpect(jsonPath("$.violations[0].source").value("SCHEMA"))
                .andExpect(jsonPath("$.violations[2].source").value("RULE"));
    }

    @Test
    @DisplayName("bulk: 100 records, 7 invalid - 202, one result per record in order, each rejected one lists every violation")
    void bulkMixIsPerRecord() throws Exception {
        List<RecordResult> results = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            results.add(i % 14 == 3
                    ? RecordResult.rejected(i, "PAYLOAD_VALIDATION_FAILED", "3 violation(s)", VIOLATIONS)
                    : RecordResult.accepted(i, UUID.randomUUID(), i + 1));
        }
        intake = new Fake(results);
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            body.append(i == 0 ? "" : ",").append("{\"i\":").append(i).append('}');
        }
        http().perform(call(BULK, body.append(']').toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.results.length()").value(100))
                .andExpect(jsonPath("$.results[3].status").value("REJECTED"))
                .andExpect(jsonPath("$.results[3].trackingId").doesNotExist())
                .andExpect(jsonPath("$.results[3].violations.length()").value(3))
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[0].violations").doesNotExist());
    }

    @Test
    @DisplayName("bulk where every record is invalid: 422 ALL_RECORDS_REJECTED with one result per record and its violations")
    void bulkAllInvalidIs422() throws Exception {
        intake = new Fake(List.of(
                RecordResult.rejected(0, "PAYLOAD_VALIDATION_FAILED", "3 violation(s)", VIOLATIONS),
                RecordResult.rejected(1, "PAYLOAD_VALIDATION_FAILED", "3 violation(s)", VIOLATIONS)));
        http().perform(call(BULK, "[{\"a\":1},{\"a\":2}]"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ALL_RECORDS_REJECTED"))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[1].violations[1].code").value("SCHEMA_REQUIRED"));
    }

    @Test
    @DisplayName("every intake refusal reason maps to a 4xx answer - never the 503 catch-all")
    void everyReasonIsMapped() throws Exception {
        for (Reason reason : Reason.values()) {
            intake = new Fake(new IntakeRefusedException(reason, "detail", List.of()));
            MvcResult result = http().perform(call(SINGLE, "{}")).andReturn();
            assertThat(result.getResponse().getStatus()).as("status of %s", reason).isBetween(400, 499);
            assertThat(result.getResponse().getContentAsString()).as("body of %s", reason)
                    .doesNotContain("INTAKE_UNAVAILABLE");
        }
    }

    /** Throws {@code refusal} on a single call, or answers a bulk with {@code results}. */
    private static final class Fake implements IntakeTransaction {

        private final RuntimeException refusal;
        private final List<RecordResult> results;

        Fake(RuntimeException refusal) {
            this.refusal = refusal;
            this.results = List.of();
        }

        Fake(List<RecordResult> results) {
            this.refusal = null;
            this.results = results;
        }

        @Override
        public AcceptedMessage accept(IntakeRequest request) {
            if (refusal != null) {
                throw refusal;
            }
            return new AcceptedMessage(UUID.randomUUID(), 1L, false);
        }

        @Override
        public List<RecordResult> acceptBulk(BulkRequest request) {
            return results;
        }
    }
}
