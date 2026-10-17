package io.integrationplatform.app.clock;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * E15 seam (F17/T144): the platform's one {@link Clock} bean. Production (every profile but {@code e2e}) gets the
 * system UTC clock, unchanged; the {@code e2e} test profile gets an {@link AdjustableClock} that starts
 * {@code platform.test-clock.offset} ahead (ISO-8601 duration, default {@code PT0S}). The offset property is read
 * only under {@code e2e}.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformClockConfiguration {

    @Bean
    @Profile("!e2e")
    Clock platformClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile("e2e")
    AdjustableClock e2eClock(@Value("${platform.test-clock.offset:PT0S}") Duration offset) {
        return new AdjustableClock(Clock.systemUTC(), offset);
    }
}
