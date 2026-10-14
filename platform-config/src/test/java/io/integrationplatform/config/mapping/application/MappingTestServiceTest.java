package io.integrationplatform.config.mapping.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.mapping.application.MappingContent;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository;
import io.integrationplatform.config.mapping.internal.persistence.MappingTestRepository;
import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E7 lane B2 (T-109 + the mapping test endpoint, backlog.md:1896-1899), unit side: the service runs the STORED
 * compiled expression on the posted sample through the TransformPort seam, resolves lookups through the
 * LookupSource seam, answers the round trip and records exactly one sample run whose {@code passed} is the
 * transform's OK - the fact lane C2's publish gate later refuses a changed-but-untested mapping on. An unknown
 * mapping, a foreign mapping and a missing sample are refused before the engine is ever called.
 */
class MappingTestServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String MAPPING_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SOURCE = "22222222-2222-2222-2222-222222222222";
    private static final String TARGET = "33333333-3333-3333-3333-333333333333";
    private static final String EXPRESSION = "{\"customer_code\": $upper(customerId)}";
    private static final String FIELD_MAPPINGS = "[{\"source\":\"customerId\",\"target\":\"customer_code\"}]";
    private static final JdbcMappingRepository.LiveMapping LIVE = new JdbcMappingRepository.LiveMapping(
            MAPPING_ID, FIELD_MAPPINGS, EXPRESSION, SOURCE, TARGET, "44444444-4444-4444-4444-444444444444");

    @Test
    @DisplayName("A passing sample runs the stored expression on the sample and records the pass")
    void aPassingSampleRunsTheStoredExpressionAndRecordsThePass() {
        StubPort transform = new StubPort();
        transform.next = new TransformPort.Result(TransformPort.Status.OK, "{\"customer_code\":\"ACME-1\"}", null);
        CapturingRuns runs = new CapturingRuns();
        MappingTestService service = service(new StubMappings(Optional.of(LIVE)), transform, runs);

        Map<String, Object> answer = service.test(MAPPING_ID,
                JSON.readTree("{\"sample\":{\"customerId\":\"acme-1\",\"odd\":{\"b\":2,\"a\":1}}}"));

        assertEquals(TransformPort.Status.OK.name(), answer.get("status"), "the pass answers OK");
        assertEquals(MAPPING_ID, answer.get("mappingId"), "the answer names the mapping");
        assertEquals(JSON.readTree("{\"customer_code\":\"ACME-1\"}"), answer.get("output"),
                "the transform's output is carried");
        assertNull(answer.get("error"), "a pass carries no error");

        assertEquals(1, transform.calls, "the engine ran once");
        assertEquals(EXPRESSION, transform.expression, "the STORED compiled expression runs");
        assertEquals(MappingContent.canonicalize(JSON.readTree(
                        "{\"customerId\":\"acme-1\",\"odd\":{\"a\":1,\"b\":2}}")).toString(),
                transform.input, "the engine sees the canonical sample, never the wrapper");

        assertEquals(1, runs.recorded.size(), "exactly one sample run is recorded");
        CapturingRuns.Entry entry = runs.recorded.get(0);
        assertEquals(UUID.fromString(MAPPING_ID), entry.mappingId());
        assertEquals(MappingContent.contentSha256Hex(MappingContent.ENGINE, SOURCE, TARGET, FIELD_MAPPINGS,
                EXPRESSION), entry.expressionHash(), "the recorded expression hash is the mapping's content hash");
        assertEquals(sha256Hex(MappingContent.canonicalize(JSON.readTree(
                        "{\"customerId\":\"acme-1\",\"odd\":{\"a\":1,\"b\":2}}")).toString()),
                entry.sampleHash(), "the recorded sample hash covers the canonical sample");
        assertTrue(entry.passed(), "an OK transform records passed");
    }

    @Test
    @DisplayName("A mapped timeout answers MAPPING_TIMEOUT and records a failed sample run")
    void aMappedTimeoutRecordsAFailedSampleRun() {
        StubPort transform = new StubPort();
        transform.next = new TransformPort.Result(TransformPort.Status.MAPPING_TIMEOUT, null,
                "the transform stopped at the 5000 ms mapping budget");
        CapturingRuns runs = new CapturingRuns();
        MappingTestService service = service(new StubMappings(Optional.of(LIVE)), transform, runs);

        Map<String, Object> answer = service.test(MAPPING_ID, JSON.readTree("{\"sample\":{}}"));

        assertEquals(TransformPort.Status.MAPPING_TIMEOUT.name(), answer.get("status"));
        assertNull(answer.get("output"), "a timeout carries no output");
        assertEquals("the transform stopped at the 5000 ms mapping budget", answer.get("error"));
        assertEquals(1, runs.recorded.size());
        assertTrue(!runs.recorded.get(0).passed(), "a timeout records a FAILED sample run");
    }

    @Test
    @DisplayName("An engine error answers ERROR and records a failed sample run")
    void anEngineErrorRecordsAFailedSampleRun() {
        StubPort transform = new StubPort();
        transform.next = new TransformPort.Result(TransformPort.Status.ERROR, null, "type mismatch");
        CapturingRuns runs = new CapturingRuns();
        MappingTestService service = service(new StubMappings(Optional.of(LIVE)), transform, runs);

        Map<String, Object> answer = service.test(MAPPING_ID, JSON.readTree("{\"sample\":{}}"));

        assertEquals(TransformPort.Status.ERROR.name(), answer.get("status"));
        assertNull(answer.get("output"));
        assertEquals("type mismatch", answer.get("error"));
        assertEquals(1, runs.recorded.size());
        assertTrue(!runs.recorded.get(0).passed(), "an error records a FAILED sample run");
    }

    @Test
    @DisplayName("An unknown mapping answers 404 and neither engine nor sample run is touched")
    void anUnknownMappingAnswers404AndRecordsNothing() {
        StubPort transform = new StubPort();
        CapturingRuns runs = new CapturingRuns();
        MappingTestService service = service(new StubMappings(Optional.empty()), transform, runs);

        ConfigApiException thrown = catchIt(() -> service.test(MAPPING_ID, JSON.readTree("{\"sample\":{}}")));

        assertEquals(404, thrown.httpStatus(), "an unknown mapping answers exactly 404");
        assertEquals(0, transform.calls, "the engine never ran");
        assertEquals(0, runs.recorded.size(), "nothing is recorded for an unknown mapping");
    }

    @Test
    @DisplayName("An unknown (or another tenant's) mapping answers 404 even when the sample is missing - the mapping"
            + " resolves before the sample is validated (B8, T109MappingTestEndpointContainerTest:128-137)")
    void anUnknownMappingWithoutASampleAnswers404NotTheSampleRefusal() {
        StubPort transform = new StubPort();
        CapturingRuns runs = new CapturingRuns();
        MappingTestService service = service(new StubMappings(Optional.empty()), transform, runs);

        ConfigApiException thrown = catchIt(() -> service.test(MAPPING_ID, JSON.readTree("{}")));

        assertEquals(404, thrown.httpStatus(), "an unknown mapping answers 404 before any sample validation");
        assertEquals(0, transform.calls, "the engine never ran");
        assertEquals(0, runs.recorded.size(), "nothing is recorded for an unknown mapping");
    }

    @Test
    @DisplayName("A post without a sample answers 400 and neither engine nor sample run is touched")
    void aMissingSampleAnswers400AndRunsNothing() {
        StubPort transform = new StubPort();
        CapturingRuns runs = new CapturingRuns();
        MappingTestService service = service(new StubMappings(Optional.of(LIVE)), transform, runs);

        ConfigApiException thrown = catchIt(() -> service.test(MAPPING_ID, JSON.readTree("{\"other\":1}")));

        assertEquals(400, thrown.httpStatus(), "a missing sample is a validation refusal");
        assertEquals(0, transform.calls, "the engine never ran");
        assertEquals(0, runs.recorded.size());
    }

    // ------------------------------------------------------------------ fakes and helpers

    /** The engine stub: records what it was asked, answers what the test queued. */
    private static final class StubPort implements TransformPort {
        int calls;
        String expression;
        String input;
        LookupSource lookups;
        Result next;

        @Override
        public Result transform(String expression, String inputJson, LookupSource lookups) {
            this.calls++;
            this.expression = expression;
            this.input = inputJson;
            this.lookups = lookups;
            return next;
        }
    }

    /** The sample-run seam stub: every record is captured for the assertions. */
    private static final class CapturingRuns implements io.integrationplatform.config.mapping.api.MappingSampleRuns {
        record Entry(UUID mappingId, String expressionHash, String sampleHash, boolean passed) {}

        final List<Entry> recorded = new ArrayList<>();

        @Override
        public void record(UUID mappingId, String expressionHash, String sampleHash, boolean passed) {
            recorded.add(new Entry(mappingId, expressionHash, sampleHash, passed));
        }

        @Override
        public Optional<Instant> lastPassing(UUID mappingId, String expressionHash) {
            return Optional.empty();
        }
    }

    /** The mapping-read stub: answers one canned live mapping for the id it was asked. */
    private static final class StubMappings implements MappingTestRepository {
        private final Optional<JdbcMappingRepository.LiveMapping> answer;

        StubMappings(Optional<JdbcMappingRepository.LiveMapping> answer) {
            this.answer = answer;
        }

        @Override
        public Optional<JdbcMappingRepository.LiveMapping> findLiveMapping(String mappingId) {
            return answer;
        }
    }

    private static MappingTestService service(StubMappings mappings, StubPort transform, CapturingRuns runs) {
        return new MappingTestService(mappings, transform, (table, key) -> "resolved", runs);
    }

    private static ConfigApiException catchIt(Runnable call) {
        try {
            call.run();
        } catch (ConfigApiException e) {
            return e;
        }
        throw new AssertionError("the call was expected to refuse with a ConfigApiException");
    }

    private static String sha256Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}