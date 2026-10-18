package io.integrationplatform.app.clock;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * E15 seam (F17/T144): the platform's one {@link Clock} bean. Production (every profile but {@code e2e}) gets the
 * system UTC clock, unchanged; the {@code e2e} test profile gets an {@link AdjustableClock} that starts
 * {@code platform.test-clock.offset} ahead (ISO-8601 duration, default {@code PT0S}). The offset property is read
 * only under {@code e2e}. Creating the e2e clock logs a WARN, and refuses to start when a production-like profile
 * (ADR-010 section 8: prod, production, staging, uat) is active too (T152): the class ships in the jar, so the guard is
 * the profile pair, not the classpath.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformClockConfiguration {

    /** ADR-010 section 8: the profiles an e2e clock must never share a context with. */
    static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging | uat";

    private static final Logger LOG = LoggerFactory.getLogger(PlatformClockConfiguration.class);

    @Bean
    @Profile("!e2e")
    Clock platformClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile("e2e")
    AdjustableClock e2eClock(@Value("${platform.test-clock.offset:PT0S}") Duration offset, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
            throw new IllegalStateException(
                    "the e2e AdjustableClock must never run with a production-like profile (" + PRODUCTION_LIKE_PROFILES + ")");
        }
        AdjustableClock clock = new AdjustableClock(Clock.systemUTC(), offset);
        LOG.warn("e2e profile active: AdjustableClock in use, offset {} - test profile only, never production", offset);
        return clock;
    }
}
