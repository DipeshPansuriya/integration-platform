package io.integrationplatform.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.ingest.port.CorrelationContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-065 (row 278 T107, E12-F1-S1 "Structured logs"): the deployable's logging configuration writes every log line
 * as one JSON object, and a line written during a message's journey carries the tenant id, the message id and the
 * correlation id as fields - parsed, not grepped.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredJsonLogTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());

    @AfterEach
    void releaseTheLoggingSystem() {
        logging.cleanUp();
    }

    @Test
    void aJourneyLogLineIsJsonWithTenantMessageAndCorrelationIds(CapturedOutput output) {
        logging.beforeInitialize();
        logging.initialize(new LoggingInitializationContext(new MockEnvironment()), "classpath:logback-spring.xml",
                null);
        UUID tenant = UUID.randomUUID();
        UUID message = UUID.randomUUID();

        try (CorrelationContext.Scope journey = CorrelationContext.open(TRACEPARENT)) {
            CorrelationContext.tag(CorrelationContext.TENANT_ID, tenant);
            CorrelationContext.tag(CorrelationContext.MESSAGE_ID, message);
            LoggerFactory.getLogger(StructuredJsonLogTest.class).info("t065 journey line");
        }

        List<String> lines = output.getOut().lines().filter(line -> line.contains("t065 journey line")).toList();
        assertThat(lines).hasSize(1);
        JsonNode json = JsonMapper.builder().build().readTree(lines.get(0));
        assertThat(json.path("message").asString()).isEqualTo("t065 journey line");
        assertThat(json.path(CorrelationContext.TENANT_ID).asString()).isEqualTo(tenant.toString());
        assertThat(json.path(CorrelationContext.MESSAGE_ID).asString()).isEqualTo(message.toString());
        assertThat(json.path(CorrelationContext.CORRELATION_ID).asString())
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }
}
