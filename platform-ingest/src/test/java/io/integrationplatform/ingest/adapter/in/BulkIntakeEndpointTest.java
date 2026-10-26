package io.integrationplatform.ingest.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.integrationplatform.ingest.port.AcceptedMessage;
import io.integrationplatform.ingest.port.IntakeRefusedException;
import io.integrationplatform.ingest.port.IntakeTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** T115 / T-128 bulk intake shape: ordered results, caps (413 named), all-invalid 422, key rules, 503 busy. */
class BulkIntakeEndpointTest {

    private static final String BULK = "/api/v1/interfaces/ORDERS/messages:bulk";

    private final FakeBulk intake = new FakeBulk();
    private final MockMvc http = MockMvcBuilders.standaloneSetup(new IntakeController(intake, 5, 200)).build();

    private static MockHttpServletRequestBuilder bulk(String body) {
        return post(BULK).header("X-Source-Contract-Version", "1.0").header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void returnsOneResultPerRecordInOrder() throws Exception {
        http.perform(bulk("[{\"id\":1},{\"bad\":true},{\"id\":3}]"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].index").value(0))
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[0].trackingId").isNotEmpty())
                .andExpect(jsonPath("$.results[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.results[1].code").value("BUSINESS_KEY_UNRESOLVED"))
                .andExpect(jsonPath("$.results[2].index").value(2))
                .andExpect(jsonPath("$.results[2].status").value("ACCEPTED"));
        assertThat(intake.last.records()).hasSize(3);
        assertThat(intake.last.idempotencyKey()).isEqualTo("k-1");
    }

    @Test
    void allRecordsInvalidIs422() throws Exception {
        http.perform(bulk("[{\"bad\":1},{\"bad\":2}]"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ALL_RECORDS_REJECTED"))
                .andExpect(jsonPath("$.results.length()").value(2));
    }

    @Test
    void overTheRecordCapIs413NamingTheCapAndNothingRuns() throws Exception {
        http.perform(bulk("[{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4},{\"id\":5},{\"id\":6}]"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("BULK_RECORDS_OVER_LIMIT"))
                .andExpect(jsonPath("$.limitRecords").value(5));
        assertThat(intake.calls).hasValue(0);
    }

    @Test
    void overTheByteCapIs413NamingTheCapAndNothingRuns() throws Exception {
        http.perform(bulk("[{\"id\":\"" + "x".repeat(250) + "\"}]"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("BULK_PAYLOAD_OVER_LIMIT"))
                .andExpect(jsonPath("$.limitBytes").value(200));
        assertThat(intake.calls).hasValue(0);
    }

    @Test
    void missingIdempotencyKeyIsRefusedAndNothingRuns() throws Exception {
        http.perform(post(BULK).header("X-Source-Contract-Version", "1.0").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":1}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(intake.calls).hasValue(0);
    }

    @Test
    void aBodyThatIsNotAnArrayIs400() throws Exception {
        http.perform(bulk("{\"id\":1}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BULK_BODY_NOT_ARRAY"));
        http.perform(bulk("[]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BULK_BODY_NOT_ARRAY"));
        assertThat(intake.calls).hasValue(0);
    }

    @Test
    void aReusedKeyWithAnotherBodyIs422() throws Exception {
        intake.behaviour = request -> {
            throw new IntakeRefusedException(IntakeRefusedException.Reason.IDEMPOTENCY_KEY_REUSED, "reused");
        };
        http.perform(bulk("[{\"id\":1}]")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void anOrderingLockTimeoutIs503WithRetryAfter() throws Exception {
        intake.behaviour = request -> {
            throw new CannotAcquireLockException("lock_timeout");
        };
        http.perform(bulk("[{\"id\":1}]")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("INTAKE_BUSY"));
        http.perform(post("/api/v1/interfaces/ORDERS/messages").header("X-Source-Contract-Version", "1.0")
                        .header("Idempotency-Key", "k-2").contentType(MediaType.APPLICATION_JSON).content("{\"id\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }

    private static final class FakeBulk implements IntakeTransaction {
        final AtomicInteger calls = new AtomicInteger();
        BulkRequest last;
        Function<BulkRequest, List<RecordResult>> behaviour = request -> {
            List<RecordResult> results = new ArrayList<>();
            for (int i = 0; i < request.records().size(); i++) {
                results.add(request.records().get(i).has("bad")
                        ? RecordResult.rejected(i, "BUSINESS_KEY_UNRESOLVED", "no key")
                        : RecordResult.accepted(i, UUID.randomUUID(), i + 1L));
            }
            return results;
        };

        @Override
        public AcceptedMessage accept(IntakeRequest request) {
            calls.incrementAndGet();
            throw new CannotAcquireLockException("lock_timeout");
        }

        @Override
        public List<RecordResult> acceptBulk(BulkRequest request) {
            calls.incrementAndGet();
            last = request;
            return behaviour.apply(request);
        }
    }
}
