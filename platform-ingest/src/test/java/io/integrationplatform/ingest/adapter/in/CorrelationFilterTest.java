package io.integrationplatform.ingest.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.ingest.port.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * T-063 (row 278 T107, E12-F1-S1): the correlation id is set at intake - every HTTP request runs inside one
 * journey scope opened from its {@code traceparent} header (a fresh trace when it has none), so the intake
 * transaction's log lines and its outbox row carry the id; nothing of it is left on the servlet thread afterwards.
 */
class CorrelationFilterTest {

    private static final String INBOUND = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final List<String> seen = new ArrayList<>();

    private void serve(MockHttpServletRequest request) throws Exception {
        new CorrelationFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            seen.add(CorrelationContext.currentTraceparent());
            seen.add(MDC.get(CorrelationContext.CORRELATION_ID));
        });
    }

    @Test
    void theInboundTraceparentIsTheRequestsJourney() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/interfaces/orders/messages");
        request.addHeader("traceparent", INBOUND);

        serve(request);

        assertThat(seen).containsExactly(INBOUND, "4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(CorrelationContext.currentTraceparent()).as("nothing left on the servlet thread").isNull();
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isNull();
    }

    @Test
    void aRequestWithoutATraceparentStartsItsOwnJourney() throws Exception {
        serve(new MockHttpServletRequest("POST", "/api/v1/interfaces/orders/messages"));

        assertThat(seen.get(0)).as("a fresh W3C traceparent").matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(seen.get(1)).isEqualTo(CorrelationContext.correlationIdOf(seen.get(0)));
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isNull();
    }
}
