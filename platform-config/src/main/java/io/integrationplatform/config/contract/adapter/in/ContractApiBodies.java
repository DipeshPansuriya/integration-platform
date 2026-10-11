package io.integrationplatform.config.contract.adapter.in;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * T-105: the documented shapes of the contract API's answers (the OpenAPI document and the typed console client read
 * them; the controller answers the same keys). Documentation records only - never instantiated.
 */
final class ContractApiBodies {

    private ContractApiBodies() {
    }

    @Schema(name = "ContractVersionBody", description = "One contract version with its schema")
    record ContractVersionBody(
            String id,
            String contractId,
            @Schema(description = "The system code the contract describes") String system,
            @Schema(allowableValues = {"SOURCE_REQUEST", "SOURCE_RESPONSE", "TARGET_REQUEST", "TARGET_RESPONSE"})
            String role,
            @Schema(description = "major.minor", example = "2.0") String versionLabel,
            @Schema(allowableValues = {"DRAFT", "PENDING_APPROVAL", "APPROVED", "RETIRED"}) String status,
            @Schema(description = "The canonical JSON Schema (draft 2020-12)") Map<String, Object> jsonSchema,
            @Schema(description = "The same schema under its E3 name (draft screens)") Map<String, Object> sourceSchema,
            @Schema(description = "The original OpenAPI document, or null for a JSON Schema import", nullable = true)
            Map<String, Object> openapiDocument,
            @Schema(description = "Lineage: the version this one derives from", nullable = true)
            String derivedFromVersionId,
            @Schema(nullable = true) String changeNote,
            String authorId,
            @Schema(nullable = true) String approverId,
            String createdAt) {
    }

    @Schema(name = "ContractVersionSummary", description = "One version in a side-by-side listing")
    record ContractVersionSummary(String id, String versionLabel,
            @Schema(allowableValues = {"DRAFT", "PENDING_APPROVAL", "APPROVED", "RETIRED"}) String status,
            @Schema(nullable = true) String derivedFromVersionId, String authorId,
            @Schema(nullable = true) String approverId, String createdAt) {
    }

    @Schema(name = "ContractBody", description = "One contract with its versions side by side, in label order")
    record ContractBody(String id, String contractId, String system,
            @Schema(allowableValues = {"SOURCE_REQUEST", "SOURCE_RESPONSE", "TARGET_REQUEST", "TARGET_RESPONSE"})
            String role,
            String code, String name, List<ContractVersionSummary> versions) {
    }

    @Schema(name = "ContractListBody", description = "Every contract of the tenant")
    record ContractListBody(List<ContractBody> contracts) {
    }

    @Schema(name = "ContractImportErrorBody",
            description = "A refused import: the refusal and, for a document that does not parse, the parser's errors")
    record ContractImportErrorBody(String error, List<String> parserErrors) {
    }
}
