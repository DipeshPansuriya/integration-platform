package io.integrationplatform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.IntegrationPlatformApplication;
import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.config.destination.Refusal;
import io.integrationplatform.scheduler.SchedulerProofSupport;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T138 (row 278, BS-17 legs 17c and 17d) at the host: the real application boots against a real database, so the
 * only thing that can stop it is the showcase rule. The showcase-only local-host exception
 * ({@value #LOCAL_HOSTS}) set without the {@code showcase} profile makes the application refuse to start; under the
 * profile it starts, logs a WARN, and the guard the application built lets the named host reach loopback while an
 * unnamed loopback literal stays refused. The profile/property matrix runs without Docker in platform-config
 * (SystemPersistenceShowcaseWiringTest). Container group (R03): ./mvnw -Pcontainer, one Docker suite at a time.
 */
@Tag("container")
@ExtendWith(OutputCaptureExtension.class)
class ShowcaseLocalHostsHostContainerTest {

    static final String LOCAL_HOSTS = "platform.config.destinations.showcase-local-hosts";
    static final String ALLOWED_HOSTS = "platform.config.destinations.allowed-hosts";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static Map<String, Object> wiring;

    @BeforeAll
    static void migratedDatabase() throws Exception {
        POSTGRES.start();
        wiring = SchedulerProofSupport.wiringProperties(POSTGRES);
        SchedulerProofSupport.migrateAsOwner(POSTGRES);
        PlatformTestWiring.grantApplicationRole(POSTGRES, new PlatformTestWiring.RestrictedLogin(
                (String) wiring.get("DB_USERNAME"), (String) wiring.get("DB_PASSWORD")));
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    private static SpringApplicationBuilder application(String... profiles) {
        return new SpringApplicationBuilder(IntegrationPlatformApplication.class)
                .profiles(profiles)
                .properties(wiring)
                .properties("server.port=0", "spring.main.banner-mode=off",
                        ALLOWED_HOSTS + "=localhost", LOCAL_HOSTS + "=localhost");
    }

    @Test
    @DisplayName("17c: the exception without the showcase profile - the application refuses to start")
    void withoutTheShowcaseProfileTheApplicationRefusesToStart() {
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext started = application().run()) {
                started.getBean(DestinationGuard.class); // reached only when the refusal is missing
            }
        }).rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LOCAL_HOSTS)
                .hasMessageContaining("showcase");
    }

    @Test
    @DisplayName("17a/17d: under the showcase profile the application starts, WARNs, and reaches the named local host")
    void underTheShowcaseProfileTheApplicationStartsAndWarns(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext started = application("showcase").run()) {
            DestinationGuard guard = started.getBean(DestinationGuard.class);

            assertThat(guard.check(URI.create("http://localhost:8089/tenant-a/orders")).pinnedAddress()
                    .isLoopbackAddress()).as("the named host reaches loopback").isTrue();
            assertThatThrownBy(() -> guard.check(URI.create("http://127.0.0.1:8089/tenant-a/orders")))
                    .as("an unnamed loopback literal stays refused")
                    .isInstanceOfSatisfying(DestinationRefusedException.class,
                            refused -> assertThat(refused.refusal()).isEqualTo(Refusal.REFUSED_ADDRESS));
        }
        assertThat(output.getAll()).contains("WARN").contains(LOCAL_HOSTS).contains("[localhost]");
    }
}
