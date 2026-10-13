package io.integrationplatform.config.mapping.adapter.in;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.integrationplatform.config.mapping.api.MappingSampleRuns;
import io.integrationplatform.transform.api.TransformPort;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-108/T-190 continuation (E7 wave 1 lane B2, row 278): the mapping test endpoint
 * {@code POST /api/v1/admin/mappings/{id}/test} - FAILING FIRST, JVM-only (no container; container
 * coverage waits for an arjun lock window). The endpoint runs the STORED compiled expression on the
 * posted sample through the TransformPort and records the sample run (E7-C5: publish is refused while
 * the expression hash differs from the last passing run - the record is what lane C2's gate reads).
 */
class MappingTestControllerTest {

    private static final UUID MAPPING_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final JdbcMappingRepository.LiveMapping LIVE =
            new JdbcMappingRepository.LiveMapping(MAPPING_ID.toString(), "[]",
                    "{'customer_code': customerId}", null, null, null);

    private record Run(UUID mappingId, String expressionHash, String sampleHash, boolean passed) {
    }

    /** A recording fake of the seam lane C2 implements - the controller must write one row per run. */
    private static final class RecordingRuns implements MappingSampleRuns {
        final List<Run> runs = new ArrayList<>();

        @Override
        public void record(UUID mappingId, String expressionHash, String sampleHash, boolean passed) {
            runs.add(new Run(mappingId, expressionHash, sampleHash, passed));
        }

        @Override
        public Optional<Instant> lastPassing(UUID mappingId, String expressionHash) {
            return Optional.empty();
        }
    }

    private static final TransformPort OK_PORT = (expression, inputJson, lookups) ->
            new TransformPort.Result(TransformPort.Status.OK, "{\"customer_code\":\"C-0001\"}", null);

    private static final TransformPort ERROR_PORT = (expression, inputJson, lookups) ->
            new TransformPort.Result(TransformPort.Status.ERROR, null,
                    "T2001: arithmetic on a string is a type mismatch");

    // Scenario: the stored expression runs on the sample and the run is recorded as passed.
    @Test
    void aStoredExpressionRunsOnTheSampleAndRecordsTheRun() {
        RecordingRuns runs = new RecordingRuns();
        MappingTestController controller = new MappingTestController(
                id -> Optional.of(LIVE), runs, OK_PORT, null);

        var answer = controller.test(MAPPING_ID.toString(), json(sample("customerId", "C-0001")));

        assertEquals(1, runs.runs.size());
        assertEquals(MAPPING_ID, runs.runs.get(0).mappingId());
        assertTrue(runs.runs.get(0).passed(), "an OK answer is a passed sample run");
        assertEquals(answer.getBody().get("expressionHash"), runs.runs.get(0).expressionHash(),
                "the recorded run is the same content the answer names");
        assertEquals(answer.getBody().get("sampleHash"), runs.runs.get(0).sampleHash());
        assertEquals("OK", answer.getBody().get("status"));
        assertNotNull(answer.getBody().get("outputJson"));
        assertNotNull(answer.getBody().get("expressionHash"));
        assertNotNull(answer.getBody().get("sampleHash"));
    }

    // The sample hash is of the sample's canonical content: keys sorted, so two writings of one
    // document produce the same hash.
    @Test
    void twoWritingsOfOneSampleDocumentHashEqual() {
        RecordingRuns first = new RecordingRuns();
        new MappingTestController(id -> Optional.of(LIVE), first, OK_PORT, null)
                .test(MAPPING_ID.toString(), json("{\"customerId\":\"C-0001\"}"));
        RecordingRuns second = new RecordingRuns();
        new MappingTestController(id -> Optional.of(LIVE), second, OK_PORT, null)
                .test(MAPPING_ID.toString(), json("{ \"customerId\" : \"C-0001\" }")); // whitespace differs only

        assertEquals(first.runs.get(0).sampleHash(), second.runs.get(0).sampleHash());
        assertEquals(64, first.runs.get(0).sampleHash().length(), "a sha-256 hex");
    }

    // Scenario: a failing expression is the test's outcome, not a server error - recorded, not thrown.
    @Test
    void aFailingExpressionIsRecordedAndAnsweredNotThrown() {
        RecordingRuns runs = new RecordingRuns();
        MappingTestController controller = new MappingTestController(id -> Optional.of(LIVE), runs, ERROR_PORT, null);
        var answer = controller.test(MAPPING_ID.toString(), json(sample("customerId", "C-0001")));

        assertEquals("ERROR", answer.getBody().get("status"));
        assertEquals(1, runs.runs.size());
        assertTrue(!runs.runs.get(0).passed(), "a failed expression is a failed sample run");
        assertTrue(((String) answer.getBody().get("error")).contains("T2001"),
                "the engine's describable error is carried: " + answer.getBody().get("error"));
    }

    // Scenario: a mapping not visible to the bound tenant (or an unknown id) answers not-found -
    // never forbidden (ADR-011 Decision 4) - and nothing is recorded.
    @Test
    void anUnknownMappingAnswersNotFoundAndRecordsNothing() {
        RecordingRuns runs = new RecordingRuns();
        MappingTestController controller = new MappingTestController(id -> Optional.empty(), runs, OK_PORT, null);

        // The controller throws the module's refusal; ConfigApiExceptionHandler renders it as HTTP 404,
        // never 403 (ADR-011 Decision 4) - the exception's own status is the designed answer here.
        var refused = assertThrows(io.integrationplatform.config.application.ConfigApiException.class,
                () -> controller.test(MAPPING_ID.toString(), json(sample("customerId", "C-0001"))));
        assertEquals(404, refused.httpStatus());
        assertEquals(0, runs.runs.size());
    }

    /** Jackson 3 parse of a literal sample document (the endpoint binds JsonNode). */
    private static tools.jackson.databind.JsonNode json(String document) {
        return JsonMapper.builder().build().readTree(document);
    }

    private static String sample(String field, String value) {
        return "{\"" + field + "\":\"" + value + "\"}";
    }
}