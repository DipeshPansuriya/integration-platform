package io.integrationplatform.config.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.config.destination.Refusal;
import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * T138 (row 278, BS-17 legs 17a-17e) through a real Spring context over the real {@link SystemPersistenceConfiguration}
 * - real profiles, the real property binder and the real system resolver ({@code localhost} resolves to loopback from
 * the hosts file, no DNS server involved). The exception is {@value #LOCAL_HOSTS}; it is honoured only under the
 * {@code showcase} profile, set without that profile the context refuses to start, and a WARN is logged while it is
 * on. The application-level boot of the same rules is ShowcaseLocalHostsHostContainerTest in platform-app.
 * Not tagged container (R03): no database, the JDBC client and the transaction manager are inert mocks.
 */
@ExtendWith(OutputCaptureExtension.class)
class SystemPersistenceShowcaseWiringTest {

    static final String LOCAL_HOSTS = "platform.config.destinations.showcase-local-hosts";

    private static final URI LOCAL_TARGET = URI.create("http://localhost:8089/tenant-a/orders");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SystemPersistenceConfiguration.class)
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withPropertyValues(SystemPersistenceConfiguration.ALLOWED_HOSTS + "=localhost");

    private static ApplicationContextRunner profiles(ApplicationContextRunner runner, String... profiles) {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles));
    }

    private static Refusal refusalOf(DestinationGuard guard, URI address) throws Exception {
        try {
            guard.check(address);
        } catch (DestinationRefusedException refused) {
            return refused.refusal();
        }
        throw new AssertionError("expected a refusal for " + address);
    }

    @Test
    @DisplayName("17a: the default mode (no profile, no exception) refuses the allow-listed local host, as today")
    void theDefaultModeRefuses(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(refusalOf(context.getBean(DestinationGuard.class), LOCAL_TARGET))
                    .isEqualTo(Refusal.REFUSED_ADDRESS);
        });
        assertThat(output.getAll()).doesNotContain(LOCAL_HOSTS);
    }

    @Test
    @DisplayName("17a/17d: under the showcase profile the named host reaches loopback, and a startup WARN says so")
    void theShowcaseProfileHonoursTheNamedHostAndWarns(CapturedOutput output) {
        profiles(runner, "showcase").withPropertyValues(LOCAL_HOSTS + "=localhost").run(context -> {
            assertThat(context).hasNotFailed();
            DestinationGuard guard = context.getBean(DestinationGuard.class);
            InetAddress pinned = guard.check(LOCAL_TARGET).pinnedAddress();
            assertThat(pinned.isLoopbackAddress()).as("pinned to the checked loopback address").isTrue();
            assertThat(refusalOf(guard, URI.create("http://127.0.0.1:8089/")))
                    .as("an unnamed loopback literal stays refused").isEqualTo(Refusal.REFUSED_ADDRESS);
        });
        assertThat(output.getAll()).contains("WARN").contains(LOCAL_HOSTS).contains("[localhost]")
                .contains("showcase");
    }

    @ParameterizedTest(name = "17c: the exception with profiles \"{0}\" refuses to start")
    @ValueSource(strings = {"", "prod", "default"})
    void theExceptionWithoutTheShowcaseProfileRefusesToStart(String activeProfiles) {
        ApplicationContextRunner configured = activeProfiles.isEmpty() ? runner
                : profiles(runner, activeProfiles.split(","));
        configured.withPropertyValues(LOCAL_HOSTS + "=localhost").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(LOCAL_HOSTS)
                    .hasMessageContaining("showcase");
        });
    }

    @Test
    @DisplayName("17b: a wildcard in the exception refuses to start, even under the showcase profile")
    void aWildcardRefusesToStart() {
        profiles(runner, "showcase").withPropertyValues(LOCAL_HOSTS + "=*.localhost").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exact host name");
        });
    }

    @Test
    @DisplayName("17e: the showcase profile without the property restores today's refusal, with no WARN")
    void removingThePropertyRestoresTodaysRefusal(CapturedOutput output) {
        profiles(runner, "showcase").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(refusalOf(context.getBean(DestinationGuard.class), LOCAL_TARGET))
                    .isEqualTo(Refusal.REFUSED_ADDRESS);
        });
        assertThat(output.getAll()).doesNotContain(LOCAL_HOSTS);
    }
}
