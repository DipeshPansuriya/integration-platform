package io.integrationplatform.ingest.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.integrationplatform.ingest.port.AcceptedMessage;
import io.integrationplatform.ingest.port.IntakeTransaction;
import io.integrationplatform.ingest.port.SyncAnswer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-129 part 1 (architecture.md section 4.2, ADR-011 Decision 5): a SYNC interface answers 200 with the mapped body
 * when the {@link SyncAnswer} port answers inside the budget; otherwise 202 with the tracking id and status
 * {@code PENDING}; with no port bean the answer is today's 202. Budget shortened from 10 s for test speed.
 */
class SyncAnswerIntakeTest {

    private static final String V1 = "/api/v1/interfaces/orders/messages";
    private static final UUID TRACKING = UUID.fromString("f0000000-0000-4000-8000-0000000000a1");
    private static final Duration BUDGET = Duration.ofMillis(200);
    private static final JsonNode MAPPED = JsonMapper.builder().build().readTree("{\"orderId\":\"O-1\",\"ok\":true}");

    private final FakePort port = new FakePort();

    @Test
    @DisplayName("SYNC, the port answers in time -> 200 with the mapped body")
    void answeredInTime() throws Exception {
        port.next = CompletableFuture.completedFuture(MAPPED);
        submit(new IntakeController(intake(true), port, BUDGET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("O-1"))
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(port.calls.get()).isEqualTo(1);
        assertThat(port.lastTracking).isEqualTo(TRACKING);
    }

    @Test
    @DisplayName("SYNC, the budget runs out -> 202 {trackingId, status: PENDING}")
    void timeoutIsPending() throws Exception {
        port.next = new CompletableFuture<>();
        submit(new IntakeController(intake(true), port, BUDGET))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trackingId").value(TRACKING.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("SYNC, an answer after the budget is ignored -> still 202 PENDING")
    void lateAnswerIsIgnored() throws Exception {
        CompletableFuture<JsonNode> late = new CompletableFuture<>();
        port.next = late;
        submit(new IntakeController(intake(true), port, BUDGET))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        late.complete(MAPPED); // arrives after the response was written; must not throw or change anything
        assertThat(late).isCompletedWithValue(MAPPED);
    }

    @Test
    @DisplayName("SYNC, the port fails -> 202 PENDING (the delivery continues asynchronously)")
    void failedAnswerIsPending() throws Exception {
        port.next = CompletableFuture.failedFuture(new IllegalStateException("target down"));
        submit(new IntakeController(intake(true), port, BUDGET))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("SYNC, no SyncAnswer bean -> today's 202 {trackingId}")
    void missingBeanKeepsToday() throws Exception {
        submit(new IntakeController(intake(true), null, BUDGET))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trackingId").value(TRACKING.toString()))
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    @Test
    @DisplayName("ASYNC interface -> 202 {trackingId}; the port is never asked")
    void asyncNeverAsksThePort() throws Exception {
        port.next = CompletableFuture.completedFuture(MAPPED);
        submit(new IntakeController(intake(false), port, BUDGET))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trackingId").value(TRACKING.toString()))
                .andExpect(jsonPath("$.status").doesNotExist());
        assertThat(port.calls.get()).isZero();
    }

    private static IntakeTransaction intake(boolean sync) {
        return request -> new AcceptedMessage(TRACKING, 1L, sync);
    }

    private static ResultActions submit(IntakeController controller) throws Exception {
        MockMvc http = MockMvcBuilders.standaloneSetup(controller).build();
        return http.perform(post(V1)
                .header("X-Source-Contract-Version", "1.0")
                .header("Idempotency-Key", "idem-sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"order_number\":\"O-1\"}"));
    }

    private static final class FakePort implements SyncAnswer {
        CompletableFuture<JsonNode> next;
        final AtomicInteger calls = new AtomicInteger();
        volatile UUID lastTracking;

        @Override
        public CompletableFuture<JsonNode> answer(UUID trackingId) {
            calls.incrementAndGet();
            lastTracking = trackingId;
            return next;
        }
    }
}
