package io.integrationplatform.config.scheduler.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.integrationplatform.config.scheduler.JobAdminService;
import io.integrationplatform.config.scheduler.PollingSchedulingBoundary;
import io.integrationplatform.domain.scheduler.PollingJobSurface.JobSummary;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

        assertThat(controller.summaries()).containsExactly(summary);
        verifyNoInteractions(service);
    }
}
