package io.integrationplatform.app.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T204: in the real application the Flyway that Spring Boot configures carries the lock-timeout callback, and the
 * whole migration set (every module's db/migration) applied through it as the migration owner. The property is not
 * set here, so this also proves the safe default (5s) is what the application runs with.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MigrationLockTimeoutSpringWiringContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        PlatformTestWiring.register(registry, POSTGRES);
    }

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("Spring Boot's Flyway carries the lock-timeout callback and applied every migration through it")
    void flywayCarriesTheCallbackAndMigratedCleanly() throws Exception {
        assertThat(Arrays.stream(flyway.getConfiguration().getCallbacks()).map(c -> c.getCallbackName()))
                .contains("migrationLockTimeout");
        assertThat(flyway.info().applied()).isNotEmpty().allMatch(m -> m.getState().isApplied());
        assertThat(flyway.info().pending()).isEmpty();
    }
}
