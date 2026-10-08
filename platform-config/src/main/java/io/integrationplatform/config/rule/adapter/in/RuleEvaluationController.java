package io.integrationplatform.config.rule.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.rule.api.RuleEvaluation;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.api.RuleSetNotFoundException;
import io.integrationplatform.config.rule.api.RuleViolation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The test panel's endpoint (T-024, E6-F3-S1): {@code POST /api/v1/admin/rule-sets/{ruleSetId}/evaluate} with
 * {@code {input}} answers {@code {ruleSetId, kind, output, violations:[{field, code, message}]}}. It holds the runtime's
 * {@link RuleEvaluationPort} and nothing else, so what the panel shows is exactly what the runtime decides; it writes
 * nothing. Another tenant's (or an unknown) id answers 404.
 */
@RestController
public class RuleEvaluationController {

    private final RuleEvaluationPort port;

    public RuleEvaluationController(RuleEvaluationPort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK: {ruleSetId, kind, output, violations}"),
        @ApiResponse(responseCode = "400", description = "The body carries no input",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "The role does not allow reading configuration",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "404", description = "Not found: the rule set is not visible to the bound tenant",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping(RuleSetAdminController.RULE_SET + "/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@PathVariable String ruleSetId, @RequestBody JsonNode body) {
        UUID id;
        try {
            id = UUID.fromString(ruleSetId);
        } catch (IllegalArgumentException notAUuid) {
            throw ConfigApiException.notFound();
        }
        JsonNode input = body == null ? null : body.get("input");
        if (input == null || input.isNull()) {
            throw ConfigApiException.validation("input is required");
        }
        RuleEvaluation evaluation;
        try {
            evaluation = port.evaluate(id, input);
        } catch (RuleSetNotFoundException notVisible) {
            throw ConfigApiException.notFound();
        }
        return ResponseEntity.ok(ConfigApiException.body(
                "ruleSetId", evaluation.ruleSetId().toString(),
                "kind", evaluation.kind(),
                "output", evaluation.output(),
                "violations", evaluation.violations().stream().map(RuleEvaluationController::view).toList()));
    }

    private static Map<String, Object> view(RuleViolation violation) {
        return ConfigApiException.body("field", violation.field(), "code", violation.code(),
                "message", violation.message());
    }
}
