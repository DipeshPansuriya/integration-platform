package io.integrationplatform.ingest.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * T-063/T-064 (row 278 T107, E12-F1-S1): one correlation id per journey. It is the W3C trace id of the journey's
 * {@code traceparent} - taken from the inbound one when it is a valid W3C value, started fresh otherwise - so the
 * id rides every Kafka record as the one header the base keeps (E8-C5) and every log line as the
 * {@code correlationId} field. A crafted value never reaches a log line: it is not a traceparent, so a fresh trace
 * starts (E12-F1-S1 @negative @tenant - the header stays opaque and never names a tenant).
 */
class CorrelationContextTest {

    private static final String INBOUND = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String W3C = "00-[0-9a-f]{32}-[0-9a-f]{16}-01";

    @AfterEach
    void nothingLeaks() {
        assertThat(CorrelationContext.currentTraceparent()).as("no scope outlives its block").isNull();
        assertThat(MDC.getCopyOfContextMap()).as("no log field outlives its scope").isNullOrEmpty();
    }

    @Test
    void aValidInboundTraceparentIsTheJourneysAndItsTraceIdIsTheCorrelationId() {
        try (CorrelationContext.Scope scope = CorrelationContext.open(INBOUND)) {
            assertThat(CorrelationContext.currentTraceparent()).isEqualTo(INBOUND);
            assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        }
    }

    @Test
    void noInboundTraceparentStartsAFreshTraceWhoseIdIsOnTheLogLine() {
        String first;
        try (CorrelationContext.Scope scope = CorrelationContext.open(null)) {
            first = CorrelationContext.currentTraceparent();
            assertThat(first).matches(W3C);
            assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isEqualTo(CorrelationContext.correlationIdOf(first));
        }
        try (CorrelationContext.Scope scope = CorrelationContext.open(null)) {
            assertThat(CorrelationContext.currentTraceparent()).as("every fresh journey gets its own id")
                    .isNotEqualTo(first);
        }
    }

    @Test
    void aCraftedOrMalformedValueIsNeverTheIdItStartsAFreshTrace() {
        for (String crafted : new String[] {"tenant=B", "00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01",
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01", INBOUND + "-tenant=B", ""}) {
            try (CorrelationContext.Scope scope = CorrelationContext.open(crafted)) {
                assertThat(CorrelationContext.currentTraceparent()).as(crafted).matches(W3C).isNotEqualTo(crafted);
                assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).as(crafted).doesNotContain("tenant");
            }
        }
    }

    @Test
    void tagsLiveInsideTheScopeOnlyAndANestedScopeRestoresTheOuterOne() {
        UUID tenant = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        CorrelationContext.tag(CorrelationContext.TENANT_ID, tenant);
        assertThat(MDC.get(CorrelationContext.TENANT_ID)).as("no tag outside a journey - it would leak").isNull();
        try (CorrelationContext.Scope outer = CorrelationContext.open(INBOUND)) {
            CorrelationContext.tag(CorrelationContext.TENANT_ID, tenant);
            try (CorrelationContext.Scope inner = CorrelationContext.open(null)) {
                CorrelationContext.tag(CorrelationContext.MESSAGE_ID, message);
                assertThat(MDC.get(CorrelationContext.MESSAGE_ID)).isEqualTo(message.toString());
            }
            assertThat(CorrelationContext.currentTraceparent()).isEqualTo(INBOUND);
            assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(MDC.get(CorrelationContext.TENANT_ID)).isEqualTo(tenant.toString());
            assertThat(MDC.get(CorrelationContext.MESSAGE_ID)).as("the inner journey's tag left with it").isNull();
        }
    }
}
