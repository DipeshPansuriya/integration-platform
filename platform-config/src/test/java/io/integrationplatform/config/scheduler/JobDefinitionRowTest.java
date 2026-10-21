package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** E14 state-change stamp: {@code stateChangedAt} is additive; the older constructors stay and default to null. */
class JobDefinitionRowTest {

    private static final Instant AT = Instant.parse("2026-10-22T08:00:00Z");

    @Test
    void theTenArgumentFormCarriesStateChangedAt() {
        JobDefinitionRow row = new JobDefinitionRow("J", "CRON", "0 0 * * *", false, false, 60, 30, "{}", null, AT);

        assertThat(row.stateChangedAt()).isEqualTo(AT);
        assertThat(row.pausedAt()).isNull();
    }

    @Test
    void theEightAndNineArgumentFormsGiveNullStateChangedAt() {
        assertThat(new JobDefinitionRow("J", "CRON", "0 0 * * *", true, false, 60, 30, "{}").stateChangedAt())
                .isNull();
        JobDefinitionRow nine = new JobDefinitionRow("J", "CRON", "0 0 * * *", true, true, 60, 30, "{}", AT);
        assertThat(nine.stateChangedAt()).isNull();
        assertThat(nine.pausedAt()).isEqualTo(AT);
    }
}
