package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.ClientBootstrap;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lane C wiring (T-157). The stamping transaction manager replaces Spring Boot's default JDBC transaction manager
 * (Boot backs off when a TransactionManager bean exists); the JDBC adapters are the {@link LoginBootstrap} port and,
 * for source systems (E4 T-098), the {@link ClientBootstrap} and {@link SourceCredentialStore} ports.
 *
 * <p>The tenant is read from the application's {@link TenantContext} at each transaction start. With no TenantContext
 * bean (before lane B's principal resolver is wired) nothing is stamped and every tenant table fails closed.
 */
@Configuration(proxyBeanMethods = false)
class TenancyPersistenceConfiguration {

    @Bean
    TenantStampingTransactionManager transactionManager(
            DataSource dataSource,
            ObjectProvider<TenantContext> tenantContexts,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        TenantContext boundTenant =
                () -> Optional.ofNullable(tenantContexts.getIfAvailable()).flatMap(TenantContext::currentTenantId);
        TenantStampingTransactionManager manager = new TenantStampingTransactionManager(dataSource, boundTenant);
        customizers.ifAvailable(customizer -> customizer.customize(manager));
        return manager;
    }

    @Bean
    LoginBootstrap loginBootstrap(JdbcTemplate jdbcTemplate) {
        return new JdbcLoginBootstrap(jdbcTemplate);
    }

    @Bean
    ClientBootstrap clientBootstrap(JdbcTemplate jdbcTemplate) {
        return new JdbcClientBootstrap(jdbcTemplate);
    }

    /** The tenant is read at each call, like the stamp: with no TenantContext bean every call fails closed. */
    @Bean
    SourceCredentialStore sourceCredentialStore(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        return new JdbcSourceCredentialStore(jdbcClient,
                () -> Optional.ofNullable(tenantContexts.getIfAvailable()).flatMap(TenantContext::currentTenantId));
    }
}
