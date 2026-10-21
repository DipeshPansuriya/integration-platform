package io.integrationplatform.config.scheduler.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.scheduler.JobAdminService;
import io.integrationplatform.config.scheduler.PollingSchedulingBoundary;
import io.integrationplatform.domain.scheduler.PollingJobSurface.JobSummary;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** E14 T-060: the controller passes the authenticated agent's name (its client id) to every operation. */
class JobAdminControllerTest {

    private final JobAdminService service = mock(JobAdminService.class);
    private final PollingSchedulingBoundary boundary = new PollingSchedulingBoundary(null);
    private final JobAdminController controller = new JobAdminController(service, boundary);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theAgentIsTheAuthenticatedName() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("ops-agent", null, List.of()));

        controller.list();
        controller.inspect("DAILY_REPORT");
        assertThat(controller.runNow("DAILY_REPORT").getStatusCode().value()).isEqualTo(202);
        controller.pause("DAILY_REPORT");
        controller.resume("DAILY_REPORT");

        verify(service).list("ops-agent");
        verify(service).inspect("ops-agent", "DAILY_REPORT");
        verify(service).runNow("ops-agent", "DAILY_REPORT");
        verify(service).pause("ops-agent", "DAILY_REPORT");
        verify(service).resume("ops-agent", "DAILY_REPORT");
    }

    @Test
    void summariesAreReadThroughTheBoundaryWithoutTouchingTheAdminService() {
        JobSummary summary = new JobSummary("DAILY_REPORT", "DAILY_REPORT", "ACTIVE", null, null);
        boundary.attachPollingSurface(() -> List.of(summary));

        assertThat(controller.summaries(null)).containsExactly(summary);
        verifyNoInteractions(service);
    }

    /** The summaries endpoint serialises JobSummary: pausedAt is an additive field, null while the job is not paused. */
    @Test
    void summariesSerialiseWithAnAdditivePausedAt() throws Exception {
        Instant at = Instant.parse("2026-10-19T09:00:00Z");
        boundary.attachPollingSurface(() -> List.of(
                new JobSummary("DAILY_REPORT", "DAILY_REPORT", "PAUSED", null, null, at),
                new JobSummary("PAYLOAD_PURGE", "PAYLOAD_PURGE", "ACTIVE", null, null)));

        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(controller.summaries(null)));

        assertThat(json.get(0).get("jobId").asString()).isEqualTo("DAILY_REPORT");
        assertThat(json.get(0).get("state").asString()).isEqualTo("PAUSED");
        assertThat(json.get(0).has("pausedAt")).isTrue();
        assertThat(json.get(0).get("pausedAt").isNull()).isFalse();
        assertThat(json.get(1).has("pausedAt")).isTrue();
        assertThat(json.get(1).get("pausedAt").isNull()).isTrue();
    }

    /** E14 state-change stamp: every old field is unchanged and {@code stateChangedAt} is the only addition. */
    @Test
    void summariesGoldenJsonKeepsEveryOldFieldAndAddsStateChangedAt() throws Exception {
        Instant next = Instant.parse("2026-10-22T10:00:00Z");
        Instant last = Instant.parse("2026-10-22T09:00:00Z");
        Instant paused = Instant.parse("2026-10-22T08:00:00Z");
        Instant changed = Instant.parse("2026-10-22T08:30:00Z");
        boundary.attachPollingSurface(() -> List.of(
                new JobSummary("DAILY_REPORT", "DAILY_REPORT", "PAUSED", next, last, paused, changed)));

        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(controller.summaries(null))).get(0);

        List<String> names = new java.util.ArrayList<>(json.propertyNames());
        assertThat(names).containsExactlyInAnyOrder("jobId", "jobType", "state", "nextRunAt", "lastRunAt",
                "pausedAt", "stateChangedAt");
        assertThat(json.get("jobId").asString()).isEqualTo("DAILY_REPORT");
        assertThat(json.get("jobType").asString()).isEqualTo("DAILY_REPORT");
        assertThat(json.get("state").asString()).isEqualTo("PAUSED");
        assertThat(json.get("nextRunAt").isNull()).isFalse();
        assertThat(json.get("lastRunAt").isNull()).isFalse();
        assertThat(json.get("pausedAt").isNull()).isFalse();
        assertThat(json.get("stateChangedAt").isNull()).isFalse();
    }

    @Test
    void summariesSinceAbsentIsTheFullListAndAValidSinceFiltersByChange() {
        Instant t = Instant.parse("2026-10-22T08:30:00Z");
        JobSummary disabled = new JobSummary("A", "A", "DISABLED", null, null, null, t);
        JobSummary quiet = new JobSummary("B", "B", "ACTIVE", null, null, null, t.minusSeconds(3600));
        boundary.attachPollingSurface(() -> List.of(disabled, quiet));

        assertThat(controller.summaries(null)).containsExactly(disabled, quiet);
        assertThat(controller.summaries("2026-10-22T08:29:59Z")).containsExactly(disabled);
        assertThat(controller.summaries("2026-10-22T08:30:01Z")).isEmpty();
    }

    @Test
    void anInvalidSinceIsRefusedWith400() {
        boundary.attachPollingSurface(() -> List.of());

        for (String bad : new String[] {"yesterday", "2026-10-22", "", "  ", "1729584000"}) {
            assertThatThrownBy(() -> controller.summaries(bad)).as(bad).isInstanceOf(ConfigApiException.class)
                    .satisfies(e -> assertThat(((ConfigApiException) e).httpStatus()).isEqualTo(400));
        }
    }
}
