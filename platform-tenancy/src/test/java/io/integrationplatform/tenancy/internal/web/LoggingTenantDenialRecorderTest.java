package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The recorder neutralises attacker-controlled request text before it reaches the log (CWE-117, CWE-176). */
class LoggingTenantDenialRecorderTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingTenantDenialRecorder.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private String loggedPath(String path) {
        new LoggingTenantDenialRecorder().record(new DeniedTenantAttempt(UUID.randomUUID(),
                DeniedTenantAttempt.Channel.PATH_ID, "GET", path, Instant.now()));
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getKeyValuePairs().stream().filter(kv -> kv.key.equals("path"))
                .map(kv -> String.valueOf(kv.value)).findFirst().orElseThrow();
    }

    @Test
    void asciiControlCharactersAreNeutralised() {
        assertThat(loggedPath("/a\r\nb\u007fc")).doesNotContain("\r", "\n", "\u007f");
    }

    @Test
    void nextLineLineAndParagraphSeparatorsAndFormatCharactersAreRemoved() {
        String hostile = "/x\u0085y\u2028z\u2029w\u202ev\u200bu\ufefft\u00ads";
        assertThat(loggedPath(hostile)).isEqualTo("/xyzwvuts");
    }

    @Test
    void theCapCountsCodePointsAndNeverSplitsASurrogatePair() {
        String path = "/" + "\uD83D\uDE00".repeat(600); // 1 + 600 supplementary code points
        String logged = loggedPath(path);
        assertThat(logged.codePointCount(0, logged.length())).isEqualTo(512);
        assertThat(Character.isHighSurrogate(logged.charAt(logged.length() - 2))).isTrue();
        assertThat(Character.isLowSurrogate(logged.charAt(logged.length() - 1))).isTrue();
    }

    @Test
    void anOrdinaryPathIsRecordedAsIs() {
        String path = "/api/v1/reports/facade/" + UUID.randomUUID();
        assertThat(loggedPath(path)).isEqualTo(path);
    }
}
