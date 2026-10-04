package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lane C wiring (T-157). The stamping transaction manager replaces Spring Boot's default JDBC transaction manager
 * (Boot backs off when a TransactionManager bean exists); the JDBC adapter is the {@link LoginBootstrap} port.
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
}
