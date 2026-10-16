package io.integrationplatform.ingest.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.integrationplatform.ingest.port.AcceptedMessage;
import io.integrationplatform.ingest.port.IntakeRefusedException;
import io.integrationplatform.ingest.port.IntakeTransaction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/**
 * T-048/T-130 (E11-F1-S1, ADR-011 Decision 4) - the HTTP shape of the ONE common API, without a database
 * (the commit contract is the container test {@code IntakeCommitContainerTest}):
 *
 * <pre>
 * Scenario: Accepted with a tracking id              -> 202 with the tracking id
 * Scenario: A record over 1 MB is refused            -> 413, the limit is named, nothing persisted
 * Scenario: An unpublished interface is refused      -> 404, nothing persisted
 * Scenario: The path cannot reach another tenant     -> 404 (tenant-scoped resolution; container proof)
 * Scenario: The unversioned path                     -> 404 (v1 only)
 * Scenario: The database fails                       -> 503 with no tracking id, the cause ERROR-logged
 * Scenario: A malformed JSON body                    -> 400, not the catch-all 503
 * Scenario: A missing or wrong Content-Type          -> 415 with the JSON error body
 * </pre>
 *
 * Header contract (ADR-011 Decision 4 plus the version pin of ADR-013): {@code X-Source-Contract-Version} and
 * {@code Idempotency-Key} are required on every call.
 */
class IntakeEndpointShapeTest {

    private static final String V1 = "/api/v1/interfaces/orders/messages";
    private static final String UNVERSIONED = "/api/interfaces/orders/messages";

    private RecordingIntake intake;
    private MockMvc http;

    @BeforeEach
    void setUp() {
        intake = new RecordingIntake();
        http = MockMvcBuilders.standaloneSetup(new IntakeController(intake)).build();
    }

    @Test
    @DisplayName("v1 path: one accepted record answers 202 with the tracking id after the transaction ran")
    void acceptedWithTrackingId() throws Exception {
        UUID tracking = UUID.fromString("f0000000-0000-4000-8000-00000000000c");
        intake.answer = new AcceptedMessage(tracking, 1L);
        http.perform(post(V1)
                        .header("X-Source-Contract-Version", "1.0")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer_code\":\"CUST-1\",\"order_number\":\"O-1\",\"total_amount\":10}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trackingId").value(tracking.toString()));
        assertThat(intake.calls.get()).isEqualTo(1);
        assertThat(intake.last.interfaceCode()).isEqualTo("orders");
        assertThat(intake.last.sourceContractVersion()).isEqualTo("1.0");
        assertThat(intake.last.idempotencyKey()).isEqualTo("idem-1");
        assertThat(intake.last.payload().get("order_number").asString()).isEqualTo("O-1");
    }

    @Test
    @DisplayName("a record over 1 MB is refused with the limit named and nothing persisted")
    void overOneMegabyteRefused() throws Exception {
        byte[] body = ("{\"customer_code\":\"" + "a".repeat(1_048_576) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        http.perform(post(V1)
                        .header("X-Source-Contract-Version", "1.0")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_OVER_LIMIT"))
                .andExpect(jsonPath("$.limitBytes").value(1_048_576));
        assertThat(intake.calls.get()).as("the intake transaction never ran").isZero();
    }

    @Test
    @DisplayName("T126 enumerated leg: a body of exactly PAYLOAD_LIMIT_BYTES is accepted 202 (pins the strict '>' cap)")
    void exactlyOneMegabyteAccepted() throws Exception {
        UUID tracking = UUID.fromString("f0000000-0000-4000-8000-00000000000d");
        intake.answer = new AcceptedMessage(tracking, 1L);
        // {"d":"aaaa..."} with the padding computed from the controller's own limit: the overhead is
        // {"d":" (6 bytes) + "} (2 bytes), all ASCII, so the UTF-8 length is exact by construction.
        byte[] body = ("{\"d\":\"" + "a".repeat(IntakeController.PAYLOAD_LIMIT_BYTES - 8) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(body).as("the body is exactly the payload limit").hasSize(IntakeController.PAYLOAD_LIMIT_BYTES);
        http.perform(post(V1)
                        .header("X-Source-Contract-Version", "1.0")
                        .header("Idempotency-Key", "idem-exact-1mb")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trackingId").value(tracking.toString()));
        assertThat(intake.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the unversioned path is 404 and never reaches the intake transaction")
    void unversionedPathIs404() throws Exception {
        http.perform(post(UNVERSIONED)
                        .header("X-Source-Contract-Version", "1.0")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        assertThat(intake.calls.get()).isZero();
    }

    @Test
    @DisplayName("an unpublished interface is refused 404 with nothing persisted")
    void unpublishedInterfaceRefused() throws Exception {
        intake.refusal = new IntakeRefusedException(
                IntakeRefusedException.Reason.INTERFACE_NOT_FOUND_OR_NOT_PUBLISHED, "orders");
        http.perform(post(V1)
                        .header("X-Source-Contract-Version", "1.0")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTERFACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a contract version that does not match the pin is refused 422 naming the pinned version")
    void contractPinMismatchRefused() throws Exception {
        intake.refusal = new IntakeRefusedException(IntakeRefusedException.Reason.CONTRACT_PIN_MISMATCH,
                "the interface pins source contract 2.0");
        http.perform(post(V1)
                        .header("X-Source-Contract-Version", "1.0")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONTRACT_PIN_MISMATCH"))
                .andExpect(jsonPath("$.message").value("the interface pins source contract 2.0"));
    }

    @Test
    @DisplayName("a call without the source contract version header is refused 400")
    void missingContractVersionHeaderRefused() throws Exception {
        http.perform(post(V1)
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONTRACT_VERSION_REQUIRED"));
        assertThat(intake.calls.get()).isZero();
    }

    @Test
    @DisplayName("a call without an Idempotency-Key is refused 400 (ADR-011 Decision 4)")
    void missingIdempotencyKeyRefused() throws Exception {
        http.perform(post(V1)
                        .header("X-Source-Contract-Version", "1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(intake.calls.get()).isZero();
    }

    @Test
    @DisplayName("a database failure answers 503 with no tracking id")
    void databaseFailureIs503() throws Exception {
        intake.unavailable = new IllegalStateException("the connection pool is exhausted");
        http.perform(post(V1)
                        .header("X-Source-Contract-Version", "1.0")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTAKE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("T126 enumerated leg: the 503 answers without a tracking id")
    void serviceUnavailableHasNoTrackingId() throws Exception {
        intake.unavailable = new IllegalStateException("the connection pool is exhausted");
        http.perform(post(V1)
                        .header("X-Source-Contract-Version", "1.0")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTAKE_UNAVAILABLE"))
                .andExpect(jsonPath("$.trackingId").doesNotExist());
    }

    @Test
    @DisplayName("T126 shape: a malformed JSON body answers 400 MALFORMED_JSON, not the catch-all 503")
    void malformedJsonIs400() throws Exception {
        http.perform(post(V1)
                        .header("X-Source-Contract-Version", "1.0")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        assertThat(intake.calls.get()).isZero();
    }

    @Test
    @DisplayName("T126 shape: a wrong Content-Type answers 415 UNSUPPORTED_MEDIA_TYPE with the JSON error body")
    void wrongContentTypeIs415() throws Exception {
        http.perform(post(V1)
                        .header("X-Source-Contract-Version", "1.0")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"a\":1}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        assertThat(intake.calls.get()).isZero();
    }

    @Test
    @DisplayName("T126 shape: a missing Content-Type answers 415, never the silent empty Spring status")
    void missingContentTypeIs415() throws Exception {
        http.perform(post(V1).header("X-Source-Contract-Version", "1.0").header("Idempotency-Key", "idem-1")
                        .content("{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        assertThat(intake.calls.get()).isZero();
    }

    @Test
    @DisplayName("T126 shape: an unexpected internal failure still answers 503 but logs the cause at ERROR (no silent swallow)")
    void unexpectedFailureIs503AndErrorLogged() throws Exception {
        Logger controllerLog = (Logger) LoggerFactory.getLogger(IntakeController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        controllerLog.addAppender(appender);
        try {
            intake.unavailable = new IllegalStateException("the connection pool is exhausted");
            http.perform(post(V1)
                            .header("X-Source-Contract-Version", "1.0")
                            .header("Idempotency-Key", "idem-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("INTAKE_UNAVAILABLE"));
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == Level.ERROR
                            && e.getThrowableProxy() != null
                            && "the connection pool is exhausted".equals(e.getThrowableProxy().getMessage()));
        } finally {
            controllerLog.detachAppender(appender);
            appender.stop();
        }
    }

    /** The stub the shape tests run against; the commit contract is the container test's business. */
    private static final class RecordingIntake implements IntakeTransaction {

        private final AtomicInteger calls = new AtomicInteger();
        private AcceptedMessage answer;
        private IntakeRefusedException refusal;
        private RuntimeException unavailable;

        IntakeTransaction.IntakeRequest last;

        @Override
        public AcceptedMessage accept(IntakeTransaction.IntakeRequest request) {
            calls.incrementAndGet();
            last = request;
            if (unavailable != null) {
                throw unavailable;
            }
            if (refusal != null) {
                throw refusal;
            }
            return answer;
        }

        JsonNode payload() {
            return last.payload();
        }
    }
}