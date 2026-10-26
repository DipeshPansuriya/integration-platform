package io.integrationplatform.ingest.adapter.in;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Documentation-only shapes of the intake answers (row 372 lane L8). The controller builds its bodies from maps, so
 * springdoc cannot see them; these records are named in the {@code @ApiResponse} content so the generated OpenAPI
 * document (and the UI client built from it) types the 422 and bulk bodies. Nothing here is serialised at runtime;
 * {@code IntakeResponseDocumentationTest} pins the field names to the keys the controller emits.
 */
public final class IntakeResponses {

    private IntakeResponses() {
    }

    /** A refusal: a stable code and a message; {@code violations} only for PAYLOAD_VALIDATION_FAILED. */
    public record IntakeErrorBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Every violation of a record that broke its contract or rules; never a submitted value or a submitted property name") List<Violation> violations) {
    }

    /** One problem with a payload: where it is (declared names only), a stable code, a message, which step found it. */
    public record Violation(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Dotted path with [index] for array items, $ for the root; an undeclared property is reported as its parent") String field,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "SCHEMA_<KEYWORD> for a schema violation, the rule row's code for a rule violation") String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Source source) {

        /** The step that found the violation. */
        public enum Source {
            SCHEMA,
            RULE
        }
    }

    /** One record's answer in a bulk call: ACCEPTED with a tracking id, or REJECTED with a code and a message. */
    public record BulkIntakeResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int index,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"ACCEPTED", "REJECTED"}) String status,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) UUID trackingId,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String message,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) List<Violation> violations) {
    }

    /** The 202 of a bulk call: one result per record, in order. */
    public record BulkIntakeResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<BulkIntakeResult> results) {
    }

    /** The 422 of a bulk call in which every record was rejected (code ALL_RECORDS_REJECTED). */
    public record BulkIntakeRejection(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<BulkIntakeResult> results) {
    }
}
