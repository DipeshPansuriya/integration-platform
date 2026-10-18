package io.integrationplatform.app.clock;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * E15 seam (F17/T144): the platform's one {@link Clock} bean. Production (every profile but {@code e2e}) gets the
 * system UTC clock, unchanged; the {@code e2e} test profile gets an {@link AdjustableClock} that starts
 * {@code platform.test-clock.offset} ahead (ISO-8601 duration, default {@code PT0S}). The offset property is read
 * only under {@code e2e}. Creating the e2e clock logs a WARN; it does not refuse to start, because the platform has
 * no production profile or marker to refuse against (T152).
 */
@Configuration(proxyBeanMethods = false)
public class PlatformClockConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformClockConfiguration.class);

    @Bean
    @Profile("!e2e")
    Clock platformClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile("e2e")
    AdjustableClock e2eClock(@Value("${platform.test-clock.offset:PT0S}") Duration offset) {
        AdjustableClock clock = new AdjustableClock(Clock.systemUTC(), offset);
        LOG.warn("e2e profile active: AdjustableClock in use, offset {} - test profile only, never production", offset);
        return clock;
    }
}
