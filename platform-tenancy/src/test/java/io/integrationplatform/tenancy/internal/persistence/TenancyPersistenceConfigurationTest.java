package io.integrationplatform.tenancy.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.ClientBootstrap;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;

/** T-157 wiring, bare: Spring Boot backs off to the stamping transaction manager; the settings hold names only (R07). */
class TenancyPersistenceConfigurationTest {

    private static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);

    private ApplicationContextRunner runner;

    @BeforeEach
    void runner() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
                        JdbcClientAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withUserConfiguration(TenancyPersistenceConfiguration.class);
    }

    @Test
    void theStampingTransactionManagerReplacesBootsDefaultAndTheBootstrapAdapterIsTheLoginBootstrap() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(TransactionManager.class)).hasSize(1);
            assertThat(context.getBean(TransactionManager.class)).isInstanceOf(TenantStampingTransactionManager.class);
            assertThat(context.getBean(LoginBootstrap.class)).isInstanceOf(JdbcLoginBootstrap.class);
            // E4 T-098: the source-system ports are the JDBC adapters too.
            assertThat(context.getBean(ClientBootstrap.class)).isInstanceOf(JdbcClientBootstrap.class);
            assertThat(context.getBean(SourceCredentialStore.class)).isInstanceOf(JdbcSourceCredentialStore.class);
        });
    }

    @Test
    void stampsTheTenantOfTheApplicationsTenantContext() {
        runner.withBean(TenantContext.class, () -> () -> Optional.of(TENANT_A)).run(context -> {
            PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
            manager.commit(manager.getTransaction(TransactionDefinition.withDefaults()));
            verify(statement).setString(1, TENANT_A.toString());
        });
    }

    @Test
    void withNoTenantContextTheContextStartsAndStampsNothing() {
        runner.run(context -> {
            PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
            manager.commit(manager.getTransaction(TransactionDefinition.withDefaults()));
            verify(connection, never()).prepareStatement(anyString());
        });
    }

    @Test
    void flywayRunsAsTheMigrationOwnerReadFromEnvironmentVariableNamesOnly() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("tenancy-database", new ClassPathResource("config/tenancy-database.yml"));

        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst().getProperty("spring.flyway.user")).hasToString("${DB_MIGRATION_USERNAME}");
        assertThat(sources.getFirst().getProperty("spring.flyway.password")).hasToString("${DB_MIGRATION_PASSWORD}");
    }
}
