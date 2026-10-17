package io.integrationplatform.app.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * E15 seam (F17/T144): the platform's one {@link Clock} bean. Outside the {@code e2e} profile it is the system UTC
 * clock and {@code platform.test-clock.offset} is ignored; inside it, an {@link AdjustableClock} starting at that
 * offset.
 */
class PlatformClockConfigurationTest {

    // SpringApplication installs Boot's ApplicationConversionService (String -> Duration) on the bean factory; the
    // bare runner does not, so it is installed here the same way.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(PlatformClockConfiguration.class);

    @Test
    void withoutAProfileTheClockIsTheSystemUtcClock() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Clock.class).doesNotHaveBean(AdjustableClock.class);
            assertThat(context.getBean(Clock.class)).isEqualTo(Clock.systemUTC());
        });
    }

    @Test
    void theOffsetPropertyIsIgnoredOutsideTheE2eProfile() {
        runner.withPropertyValues("platform.test-clock.offset=P14D").run(context -> {
            assertThat(context).hasSingleBean(Clock.class).doesNotHaveBean(AdjustableClock.class);
            assertThat(context.getBean(Clock.class)).isEqualTo(Clock.systemUTC());
        });
    }

    @Test
    void theE2eProfileGivesAnAdjustableClockStartingAtTheOffset() {
        runner.withPropertyValues("spring.profiles.active=e2e", "platform.test-clock.offset=P14D").run(context -> {
            assertThat(context).hasSingleBean(Clock.class).hasSingleBean(AdjustableClock.class);
            Instant expected = Instant.now().plus(Duration.ofDays(14));
            assertThat(context.getBean(Clock.class).instant()).isCloseTo(expected, within(1, ChronoUnit.MINUTES));
        });
    }

    @Test
    void theE2eProfileWithoutAnOffsetStartsAtNow() {
        runner.withPropertyValues("spring.profiles.active=e2e").run(context -> {
            assertThat(context.getBean(AdjustableClock.class).offset()).isEqualTo(Duration.ZERO);
        });
    }
}
