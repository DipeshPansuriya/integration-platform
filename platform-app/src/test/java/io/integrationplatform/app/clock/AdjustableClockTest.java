package io.integrationplatform.app.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** E15 seam (F17/T144): the e2e-profile clock that SC-04 ("two weeks later") advances. */
class AdjustableClockTest {

    private static final Instant BASE = Instant.parse("2026-10-17T09:30:00Z");
    private static final Clock FIXED = Clock.fixed(BASE, ZoneOffset.UTC);

    @Test
    void startsAtTheBaseClockPlusTheConfiguredOffset() {
        assertThat(new AdjustableClock(FIXED, Duration.ZERO).instant()).isEqualTo(BASE);
        assertThat(new AdjustableClock(FIXED, Duration.ofHours(3)).instant()).isEqualTo(BASE.plus(Duration.ofHours(3)));
    }

    @Test
    void advancingFourteenDaysMovesTheInstantExactlyFourteenDays() {
        AdjustableClock clock = new AdjustableClock(FIXED, Duration.ZERO);

        clock.advance(Duration.parse("P14D"));

        assertThat(clock.instant()).isEqualTo(BASE.plus(Duration.ofDays(14)));
        assertThat(clock.millis()).isEqualTo(BASE.plus(Duration.ofDays(14)).toEpochMilli());
        assertThat(clock.offset()).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void advancesAccumulate() {
        AdjustableClock clock = new AdjustableClock(FIXED, Duration.ofDays(1));

        clock.advance(Duration.ofDays(2));
        clock.advance(Duration.ZERO);

        assertThat(clock.instant()).isEqualTo(BASE.plus(Duration.ofDays(3)));
    }

    @Test
    void timeNeverMovesBackward() {
        AdjustableClock clock = new AdjustableClock(FIXED, Duration.ofDays(1));

        assertThatIllegalArgumentException().isThrownBy(() -> clock.advance(Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> clock.advance(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new AdjustableClock(FIXED, Duration.ofDays(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new AdjustableClock(FIXED, null));
        assertThat(clock.instant()).as("a refused advance changes nothing").isEqualTo(BASE.plus(Duration.ofDays(1)));
    }

    @Test
    void runsInUtcAndWithZoneKeepsTheOffset() {
        AdjustableClock clock = new AdjustableClock(FIXED, Duration.ZERO);
        clock.advance(Duration.ofDays(14));

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);

        Clock india = clock.withZone(ZoneId.of("Asia/Kolkata"));
        assertThat(india.getZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThat(india.instant()).as("same instant, other zone").isEqualTo(BASE.plus(Duration.ofDays(14)));
    }
}
