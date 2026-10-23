package io.integrationplatform.tenantdenial;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * T193: the report day of {@link T186ReportMatrix} must follow the clock. DeliveryReportController answers 400 for a day
 * after today or older than platform.delivery.report.max-age-days (default 31, UTC), so a fixed date turns the forged-tenant
 * suite red about a month after it was written.
 */
class T186ReportMatrixDayTest {

    private static final int MAX_AGE_DAYS = 31;

    @Test
    void dayStaysInsideTheControllersWindowOnAnyDate() {
        for (String now : new String[] {"2026-10-23T00:00:00Z", "2026-11-22T12:00:00Z", "2027-03-01T23:59:59Z",
                "2028-02-29T00:00:01Z"}) {
            LocalDate today = LocalDate.ofInstant(Instant.parse(now), ZoneOffset.UTC);
            LocalDate day = LocalDate.parse(T186ReportMatrix.dayFor(Clock.fixed(Instant.parse(now), ZoneOffset.UTC)));
            assertThat(day).as("day for %s", now).isBetween(today.minusDays(MAX_AGE_DAYS), today);
        }
    }

    @Test
    void dayIsNotTodayToSurviveAMidnightRolloverBetweenSeedAndRequest() {
        Instant now = Instant.parse("2026-11-22T23:59:59Z");
        assertThat(LocalDate.parse(T186ReportMatrix.dayFor(Clock.fixed(now, ZoneOffset.UTC))))
                .isBefore(LocalDate.ofInstant(now, ZoneOffset.UTC));
    }
}
