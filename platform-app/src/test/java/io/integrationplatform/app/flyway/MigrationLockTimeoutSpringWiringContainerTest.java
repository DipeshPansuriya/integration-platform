package io.integrationplatform.app.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
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
 * T204: in the real application the Flyway that Spring Boot configures carries the lock-timeout init-sql with the
 * safe default (IPAAS_FLYWAY_LOCK_TIMEOUT is not set here), and the whole migration set (every module's db/migration)
 * applied through it as the migration owner.
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
    @DisplayName("Spring Boot's Flyway runs with SET lock_timeout = '5s' and applied every migration through it")
    void flywayCarriesTheDefaultTimeoutAndMigratedCleanly() {
        assertThat(flyway.getConfiguration().getInitSql()).isEqualTo("SET lock_timeout = '5s'");
        assertThat(flyway.info().applied()).isNotEmpty().allMatch(m -> m.getState().isApplied());
        assertThat(flyway.info().pending()).isEmpty();
    }
}
