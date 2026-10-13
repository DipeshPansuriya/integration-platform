package io.integrationplatform.config.mapping.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.mapping.api.MappingSampleRuns;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository;
import io.integrationplatform.config.mapping.internal.persistence.MappingTestRepository;
import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mapping test endpoint (E7 lane B2, T-109 + backlog.md:1896-1899): {@code POST /api/v1/admin/mappings/{id}/test}
 * runs the STORED compiled expression on one posted sample through the {@link TransformPort} seam - lookups resolve
 * through the {@link LookupSource} seam - and records exactly one sample run whose {@code passed} is the transform's
 * OK: lane C2's publish gate later refuses a mapping whose expression content has no passing run since its last
 * change. The hash of the recorded run is the mapping's one canonical content hash (ADR-013), so the gate's
 * comparison is the save identity, never a re-canonicalized guess. The whole round trip sits in one transaction:
 * the tenant-scoped mapping read and (once C2's JDBC bean replaces this lane's no-op) the sample-run record share it.
 */
public class MappingTestService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MappingTestRepository mappings;
    private final TransformPort transform;
    private final LookupSource lookups;
    private final MappingSampleRuns sampleRuns;

    public MappingTestService(MappingTestRepository mappings, TransformPort transform, LookupSource lookups,
            MappingSampleRuns sampleRuns) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.transform = Objects.requireNonNull(transform, "transform");
        this.lookups = Objects.requireNonNull(lookups, "lookups");
        this.sampleRuns = Objects.requireNonNull(sampleRuns, "sampleRuns");
    }

    /**
     * One sample round trip: the answer carries the mapping's id, the transform's status, its parsed output (null
     * unless OK) and the engine's error text (null unless ERROR/MAPPING_TIMEOUT) - a pass, a timeout and an error
     * all answer 200 with the honest status.
     */
    @Transactional
    public Map<String, Object> test(String mappingId, JsonNode body) {
        JsonNode sample = sampleOf(body);
        String canonicalSample = MappingContent.canonicalize(sample).toString();

        // A non-uuid path id finds no row, exactly like a foreign one: the same 404, never a 500.
        JdbcMappingRepository.LiveMapping live =
                mappings.findLiveMapping(mappingId).orElseThrow(ConfigApiException::notFound);

        TransformPort.Result result = transform.transform(live.compiledExpression(), canonicalSample, lookups);
        boolean passed = result.status() == TransformPort.Status.OK;
        sampleRuns.record(UUID.fromString(live.id()), expressionHashOf(live), sha256Hex(canonicalSample), passed);
        return ConfigApiException.body(
                "mappingId", live.id(),
                "status", result.status().name(),
                "output", result.outputJson() == null ? null : JSON.readTree(result.outputJson()),
                "error", result.error());
    }

    /** The mandatory posted sample document; its absence is a validation refusal before anything else runs. */
    private static JsonNode sampleOf(JsonNode body) {
        JsonNode sample = body == null ? null : body.get("sample");
        if (sample == null || sample.isNull() || sample.isMissingNode()) {
            throw ConfigApiException.validation("the mandatory sample is missing");
        }
        return sample;
    }

    /** The mapping's own canonical content hash - the identity the publish gate compares the last pass against. */
    private static String expressionHashOf(JdbcMappingRepository.LiveMapping live) {
        return MappingContent.contentSha256Hex(MappingContent.ENGINE, live.sourceContractVersionId(),
                live.targetContractVersionId(), live.fieldMappingsJson(), live.compiledExpression());
    }

    private static String sha256Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JVM", e);
        }
    }
}