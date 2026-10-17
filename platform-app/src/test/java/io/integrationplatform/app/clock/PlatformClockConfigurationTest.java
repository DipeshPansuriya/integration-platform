package io.integrationplatform.app.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * E15 seam (F17/T144): the platform's one {@link Clock} bean. Outside the {@code e2e} profile it is the system UTC
 * clock and {@code platform.test-clock.offset} is ignored; inside it, an {@link AdjustableClock} starting at that
 * offset.
 */
@ExtendWith(OutputCaptureExtension.class)
class PlatformClockConfigurationTest {

    private static final String E2E_WARNING = "e2e profile active: AdjustableClock in use";

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

    @Test
    void theE2eProfileLogsAWarningThatTheTestClockIsInUse(CapturedOutput output) {
        runner.withPropertyValues("spring.profiles.active=e2e", "platform.test-clock.offset=P14D").run(context -> {
            assertThat(context).hasSingleBean(AdjustableClock.class);
            assertThat(output).contains("WARN").contains(E2E_WARNING).contains("PT336H");
        });
    }

    @Test
    void withoutTheE2eProfileNoTestClockWarningIsLogged(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(output).doesNotContain(E2E_WARNING);
        });
    }
}
