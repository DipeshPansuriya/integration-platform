package io.integrationplatform.config.mapping.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigErrorBody;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.mapping.api.MappingSampleRuns;
import io.integrationplatform.config.mapping.application.MappingContent;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository;
import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformPort;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * E7 wave 1 lane B2 (sanjay-developer, row 278): the mapping test endpoint
 * {@code POST /api/v1/admin/mappings/{id}/test} - the STORED compiled expression runs on the posted
 * sample document through the TransformPort and the outcome is recorded as one sample run
 * ({@code MappingSampleRuns.record(mappingId, expressionHash, sampleHash, passed)}) - the record lane
 * C2's publish gate reads (E7-C5: publish is refused while the expression content differs from the
 * last passing run). A failing expression is the TEST's outcome, not a server error: 200 with the
 * status and the engine's describable error; a mapping not visible to the bound tenant answers 404,
 * never 403 (ADR-011 Decision 4).
 *
 * <p>The engine is constructed here with the budget resolved from {@code config/transform.yml}
 * (T-109's body, imported by application.yml) - module wiring stays in the module with Spring types
 * (ADR-006 rule 5's pool is shared inside the engine). Until lane C2's JDBC
 * {@code MappingSampleRuns} bean lands, the seam's no-op is wired (the seam's own note: "Bean wiring
 * is lane B2's"). Lookups resolve through any configured {@code LookupSource} bean; until T-163's
 * implementation lands the wiring answer is the null-source fallback (a lookup on a sample answers
 * not-found, clearly visible in the test's output).</p>
 */
@RestController
@RequestMapping("/api/v1/admin/mappings")
public class MappingTestController {

    /** The by-id read the repository carries: one mapping or none visible to the bound tenant. */
    private final Function<String, java.util.Optional<JdbcMappingRepository.LiveMapping>> mappingsById;
    private final MappingSampleRuns sampleRuns;
    private final TransformPort transforms;
    private final LookupSource lookups;

    /** The testing seam the JVM tests wire: by-id read, sample-run recorder, engine, lookups (null = none). */
    MappingTestController(Function<String, java.util.Optional<JdbcMappingRepository.LiveMapping>> mappingsById,
            MappingSampleRuns sampleRuns, TransformPort transforms, LookupSource lookups) {
        this.mappingsById = Objects.requireNonNull(mappingsById, "mappingsById");
        this.sampleRuns = Objects.requireNonNull(sampleRuns, "sampleRuns");
        this.transforms = Objects.requireNonNull(transforms, "transforms");
        this.lookups = lookups;
    }

    public MappingTestController(JdbcMappingRepository repository,
            ObjectProvider<MappingSampleRuns> sampleRuns, ObjectProvider<LookupSource> lookupSources,
            @Value("${transform.budget-millis:5000}") long budgetMillis,
            @Value("${transform.max-depth:200}") int maxDepth) {
        this(repository::findMappingById,
                sampleRuns.getIfAvailable(MappingSampleRuns::noop),
                engine(budgetMillis, maxDepth),
                lookupSources.getIfAvailable(() -> null));
    }

    /**
     * The wiring constructs the engine through its fully-qualified name: no engine type appears in
     * any signature or import of this module (ADR-006 rule 1 - the port surface is all a caller sees).
     */
    private static TransformPort engine(long budgetMillis, int maxDepth) {
        return new io.integrationplatform.transform.engine.JsonataTransformEngine(budgetMillis, maxDepth);
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description =
            "The run's outcome: status OK (output, passed) or ERROR / MAPPING_TIMEOUT (error, failed);"
            + " both are recorded as sample runs"),
        @ApiResponse(responseCode = "404", description = "Not found: the mapping is not visible to the bound tenant",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConfigErrorBody.class)))})
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String id, @RequestBody JsonNode body) {
        UUID mappingId = mappingId(id);
        JdbcMappingRepository.LiveMapping live = mappingsById.apply(id).orElse(null);
        if (live == null) {
            throw ConfigApiException.notFound();
        }
        String sampleJson = MappingContent.canonicalFieldMappings(body.toString());
        String expressionHash = MappingContent.contentSha256Hex(MappingContent.ENGINE,
                live.sourceContractVersionId(), live.targetContractVersionId(),
                live.fieldMappingsJson(), live.compiledExpression());
        String sampleHash = sha256Hex(sampleJson);

        TransformPort.Result result = transforms.transform(live.compiledExpression(), sampleJson, lookups);
        boolean passed = result.status() == TransformPort.Status.OK;
        sampleRuns.record(mappingId, expressionHash, sampleHash, passed);
        return ResponseEntity.ok(ConfigApiException.body(
                "mappingId", mappingId,
                "status", result.status().name(),
                "outputJson", result.outputJson(),
                "error", result.error(),
                "passed", passed,
                "expressionHash", expressionHash,
                "sampleHash", sampleHash));
    }

    /** A malformed id is a validation refusal, not a 500. */
    private static UUID mappingId(String id) {
        try {
            return UUID.fromString(Objects.requireNonNull(id, "id"));
        } catch (IllegalArgumentException e) {
            throw ConfigApiException.validation("the mapping id is not a uuid: " + id);
        }
    }

    private static String sha256Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JVM", e);
        }
    }

}