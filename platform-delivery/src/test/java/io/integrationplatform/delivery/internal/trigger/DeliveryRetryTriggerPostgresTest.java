package io.integrationplatform.delivery.internal.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.delivery.port.DeliveryRetryStore;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-090 (E2-F2-S3): the container proof that the delivery-retry trigger is reachable to the REAL store - and the
 * honest statement that it cannot pass until lane C integrates {@link DeliveryRetryStore} (T-012, T-013). That is
 * the seam's explicit design (the brief): lane B's behaviour is proven green against the contract-honouring fake
 * ({@code FakeDeliveryRetryStore}); this test is committed failing and flips green the moment the composed
 * delivery context exposes the real store bean. The two-instance exactly-once claim on the real
 * {@code FOR UPDATE SKIP LOCKED} table belongs to lane C's own contract test (the store javadoc's promise).
 *
 * <p>Pinned PostgreSQL image (R05); Flyway applies every migration on the classpath as the container owner.
 */
@Tag("container")
@Testcontainers
class DeliveryRetryTriggerPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.11-alpine")).withDatabaseName("ipaas_t31");

    @Test
    void theComposedDeliveryContextExposesLanesRetryStore() {
        SpringApplication application = new SpringApplication(TestApplication.class);
        application.setDefaultProperties(Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword(),
                "spring.main.banner-mode", "off",
                "logging.level.root", "warn"));
        try (ConfigurableApplicationContext context = application.run("--spring.main.web-application-type=none")) {

            assertThat(context.getBean(DataSource.class))
                    .as("Flyway has applied the migrations: the database side of the seam is live")
                    .isNotNull();
            DeliveryRetryStore store = context.getBeanProvider(DeliveryRetryStore.class).getIfAvailable();

            assertThat(store)
                    .as("EXPECTED RED until T-012/T-013 integrate: the trigger needs the real DeliveryRetryStore "
                            + "as a bean in the delivery context")
                    .isNotNull();
        }
    }

    @SpringBootApplication(scanBasePackages = "io.integrationplatform.delivery")
    static class TestApplication {
    }
}
