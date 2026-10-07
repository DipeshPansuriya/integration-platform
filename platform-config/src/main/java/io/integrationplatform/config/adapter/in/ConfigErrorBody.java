package io.integrationplatform.config.adapter.in;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation only: the JSON shape of every config admin refusal, as {@code ConfigApiException} builds it -
 * {@code {"error": <message>}} for a validation, not-found or conflict answer, {@code {"refusal": <code>}} where the
 * scenario names the refusal (NO_BOUND_USER, AUTHOR_IS_APPROVER, NOT_PENDING_APPROVAL). Exactly one key is present.
 * Nothing builds this record; the controllers name it in their {@code @ApiResponse} declarations.
 */
@Schema(name = "ConfigErrorBody", description = "A config admin refusal: exactly one of error or refusal is present.")
public record ConfigErrorBody(
        @Schema(description = "The refusal message (validation, not found, conflict).") String error,
        @Schema(description = "The named refusal code (NO_BOUND_USER, AUTHOR_IS_APPROVER, NOT_PENDING_APPROVAL).")
        String refusal) {
}
