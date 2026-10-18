package io.integrationplatform.config.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Row 327 T97 (T-184, ADR-010 section 8): the platform's own hosts (its instances, PostgreSQL, Kafka, Keycloak, the
 * observability stack) are a configured list, {@value #PLATFORM_HOSTS}, bound by the real
 * {@link SystemPersistenceConfiguration} into the one guard; a platform host is refused even when the operator's
 * allow-list names it. Not tagged container (R03): the JDBC client and the transaction manager are inert mocks.
 */
class SystemPersistencePlatformHostsWiringTest {

    static final String PLATFORM_HOSTS = "platform.config.destinations.platform-hosts";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SystemPersistenceConfiguration.class)
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withPropertyValues(SystemPersistenceConfiguration.ALLOWED_HOSTS + "=*.platform.example,*.partner.example");

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("The configured platform hosts reach the guard: allow-listed or not, they are refused")
    void theConfiguredPlatformHostsAreRefused() {
        runner.withPropertyValues(PLATFORM_HOSTS + "=postgres.platform.example,kafka.platform.example")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DestinationGuard guard = context.getBean(DestinationGuard.class);
                    DestinationRefusedException refused = catchThrowableOfType(DestinationRefusedException.class,
                            () -> guard.checkHost("postgres.platform.example"));
                    assertThat(refused).isNotNull();
                    assertThat(refused.detail()).isEqualTo("platform-host");
                    guard.checkHost("other.platform.example"); // allow-listed, not a platform host: saves
                });
    }
}
