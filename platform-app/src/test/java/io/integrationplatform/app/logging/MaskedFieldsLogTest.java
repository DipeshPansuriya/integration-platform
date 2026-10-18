package io.integrationplatform.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.flow.api.MaskedFields;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * T-066 / E12-F1-S2 (row 278 T108): a masked field's value never reaches a log line. The JSON encoder the app ships
 * ({@code logback-spring.xml} + the customizer {@code application.yml} names) scrubs every value intake registered,
 * whatever logger wrote it - so a target's error echo or an exception text cannot carry it out. Not tagged container.
 */
@ExtendWith(OutputCaptureExtension.class)
class MaskedFieldsLogTest {

    private static final String PROBE = "MASK-PROBE-7Q3Z";

    private final LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());

    @AfterEach
    void releaseTheLoggingSystem() {
        logging.cleanUp();
    }

    @Test
    void aRegisteredMaskedValueIsReplacedInTheJsonLogLineTheAppWrites(CapturedOutput output) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        logging.beforeInitialize();
        logging.initialize(new LoggingInitializationContext(environment), "classpath:logback-spring.xml", null);
        MaskedFields.register(List.of(PROBE));

        LoggerFactory.getLogger(MaskedFieldsLogTest.class)
                .warn("t108 delivery: target answered HTTP 400 customerId {} unknown", PROBE);

        List<String> lines = output.getOut().lines().filter(line -> line.contains("t108 delivery")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).doesNotContain(PROBE).contains(MaskedFields.MARKER);
    }
}
