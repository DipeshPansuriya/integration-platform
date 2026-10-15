package io.integrationplatform.app.queue;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F10-narrow (row278 release-base plan step 8): the read-only queue summary the console's dashboard shows -
 * queued / processed / converted / failed per flow + day, derived live by {@link QueueSummaryService} from the
 * delivery/message state tables. READS ONLY: the endpoint takes no body and writes nothing.
 *
 * <p>The tenant never travels in the path (ADR-011 Decision 4): it comes from the bearer, bound by the security
 * chain, and the figures are always the bound tenant's rows. The operation rides the existing
 * {@code READ_MESSAGE_TIMELINE} matrix (the queue figures are a read of message/delivery state, not configuration)
 * - the rule is declared in {@link QueueEndpointRoles} and guard-checked by platform-app's completeness test.
 */
@RestController
@RequestMapping(QueueSummaryController.PATH)
public class QueueSummaryController {

    static final String PATH = "/api/v1/admin/queue";

    private final QueueSummaryService service;
    private final ConfigActors actors;

    public QueueSummaryController(QueueSummaryService service, ConfigActors actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "A bad query value (a flowId that is not a uuid, a non-ISO date)",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class))),
        @ApiResponse(responseCode = "403", description = "NO_BOUND_USER, or ROLE_NOT_GRANTED: the figures are message-timeline reads",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @GetMapping("/summary")
    public ResponseEntity<List<Map<String, Object>>> summary(
            @RequestParam(value = "flowId", required = false) UUID flowId,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        actors.requireActor();
        List<Map<String, Object>> rows = service.summary(flowId, date).stream()
                .map((row) -> Map.<String, Object>of(
                        "flowId", row.flowId(), "flowCode", row.flowCode(), "flowName", row.flowName(),
                        "day", row.day(), "queued", row.queued(), "processed", row.processed(),
                        "converted", row.converted(), "failed", row.failed()))
                .toList();
        return ResponseEntity.ok(rows);
    }
}