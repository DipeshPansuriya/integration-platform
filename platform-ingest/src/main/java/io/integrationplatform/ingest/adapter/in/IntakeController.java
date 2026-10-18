package io.integrationplatform.ingest.adapter.in;

import io.integrationplatform.ingest.port.AcceptedMessage;
import io.integrationplatform.ingest.port.IntakeRefusedException;
import io.integrationplatform.ingest.port.IntakeTransaction;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ONE common intake endpoint (E11 A1 narrow, T-048/T-130; ADR-011 Decision 4):
 * {@code POST /api/v1/interfaces/{interfaceCode}/messages}. v1 only - the unversioned path has no mapping and
 * answers 404. Headers on every call: {@code X-Source-Contract-Version} (ADR-013's interface pin check) and
 * {@code Idempotency-Key} (its replay/store is E11 B1 - presence is required here).
 *
 * <p>Response table (ADR-011 Decision 4; T126 shape handlers): 202 with the tracking id; 400 a required header
 * missing or a malformed JSON body; 415 a missing or wrong Content-Type; 413 the payload cap (named); 404 an
 * unknown/unpublished interface; 422 an off-pin version or unresolved business key; 503 the transaction could not
 * commit (no tracking id, cause ERROR-logged). The size cap is checked here, before the transaction runs - an
 * over-cap record never reaches the database.
 */
@RestController
public class IntakeController {

    /** The 1 MB payload cap (ADR-011 Decision 4; the bulk caps for batch records are E11 B2's). */
    static final int PAYLOAD_LIMIT_BYTES = 1_048_576;

    private static final Logger log = LoggerFactory.getLogger(IntakeController.class);

    private final IntakeTransaction intake;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public IntakeController(IntakeTransaction intake) {
        this.intake = intake;
    }

    // T126: the Content-Type check is IN the method (not consumes=) so the refusal carries the JSON error body
    // instead of Spring's silent empty 415; the contract stays application/json per ADR-011 Decision 4.
    // Row 278 gate fix: the declared response table (ADR-011 Decision 4) so the generated console client and T094
    // see every answered status - 403 is the role guard refusing a non-source caller (SUBMIT_MESSAGE is source-only).
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Accepted: the message is recorded; the body carries the tracking id"),
        @ApiResponse(responseCode = "400", description = "A required header is missing or the JSON body is malformed"),
        @ApiResponse(responseCode = "403", description = "Forbidden: intake is source-only - a human or non-source caller is refused"),
        @ApiResponse(responseCode = "404", description = "The interface is unknown or unpublished"),
        @ApiResponse(responseCode = "413", description = "The payload exceeds the 1 MB cap"),
        @ApiResponse(responseCode = "415", description = "The Content-Type is missing or not application/json"),
        @ApiResponse(responseCode = "422", description = "The source contract version is off-pin or a business key is unresolved"),
        @ApiResponse(responseCode = "503", description = "The intake transaction could not commit; no tracking id")})
    @PostMapping(value = "/api/v1/interfaces/{interfaceCode}/messages")
    public ResponseEntity<Map<String, Object>> submit(
            @PathVariable("interfaceCode") String interfaceCode,
            @RequestHeader(value = "Content-Type", required = false) MediaType contentType,
            @RequestHeader(value = "X-Source-Contract-Version", required = false) String sourceContractVersion,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "the request body must be application/json");
        }
        if (sourceContractVersion == null || sourceContractVersion.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "CONTRACT_VERSION_REQUIRED",
                    "X-Source-Contract-Version is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
        }
        if (body.length > PAYLOAD_LIMIT_BYTES) {
            Map<String, Object> refusal = errorBody("PAYLOAD_OVER_LIMIT",
                    "the record exceeds the " + PAYLOAD_LIMIT_BYTES + " byte payload limit");
            refusal.put("limitBytes", PAYLOAD_LIMIT_BYTES);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(refusal);
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (StreamReadException malformed) {
            return error(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "the request body is not valid JSON");
        }
        AcceptedMessage accepted = intake.accept(
                new IntakeTransaction.IntakeRequest(interfaceCode, sourceContractVersion, payload, idempotencyKey));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("trackingId", accepted.trackingId().toString());
        return ResponseEntity.accepted().body(answer);
    }

    @ExceptionHandler(value = IntakeRefusedException.class)
    ResponseEntity<Map<String, Object>> refused(IntakeRefusedException refusal) {
        String code = switch (refusal.reason()) {
            case INTERFACE_NOT_FOUND_OR_NOT_PUBLISHED -> "INTERFACE_NOT_FOUND";
            case CONTRACT_PIN_MISMATCH -> "CONTRACT_PIN_MISMATCH";
            case BUSINESS_KEY_UNRESOLVED -> "BUSINESS_KEY_UNRESOLVED";
        };
        HttpStatus status = refusal.reason() == IntakeRefusedException.Reason.INTERFACE_NOT_FOUND_OR_NOT_PUBLISHED
                ? HttpStatus.NOT_FOUND
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return error(status, code, refusal.detail());
    }

    @ExceptionHandler(value = Exception.class)
    ResponseEntity<Map<String, Object>> unavailable(Exception failure) {
        log.error("intake answered 503 - unexpected failure behind the common intake endpoint", failure);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "INTAKE_UNAVAILABLE", "the intake transaction did not commit");
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return body;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(errorBody(code, message));
    }
}