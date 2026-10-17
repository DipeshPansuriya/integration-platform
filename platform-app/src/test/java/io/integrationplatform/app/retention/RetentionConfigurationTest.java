package io.integrationplatform.app.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Period;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Row327 T118 (E12-F2-S3, T-135), Gherkin "Retention settings": given the logging, metrics, tracing and audit
 * configuration, when it is read by the configuration test, then the retentions are 30 days, 13 months, 7 days and
 * 7 years (PRD "Retention"; architecture.md "Audit - partition drop after 7 years"). The figures are read from the
 * application context the platform boots, not from the record's constant alone. Not tagged container (R03).
 */
class RetentionConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RetentionConfiguration.class);

    @Test
    void theRetentionsAre30Days13Months7DaysAnd7Years() {
        context.run(app -> {
            RetentionPolicy retention = app.getBean(RetentionPolicy.class);

            assertThat(retention.logs()).isEqualTo(Duration.ofDays(30));
            assertThat(retention.metrics()).isEqualTo(Period.ofMonths(13));
            assertThat(retention.traces()).isEqualTo(Duration.ofDays(7));
            assertThat(retention.audit()).isEqualTo(Period.ofYears(7));
        });
    }
}
