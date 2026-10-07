package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.port.ApiOperationRules;
import io.integrationplatform.tenancy.port.RoleGrants;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * T-099 wiring: the role check ({@link RoleEnforcementInterceptor}) on every {@code /api/**} handler. Spring MVC runs
 * it after the security filter chains - the bearer chain and the console session chain - have bound the caller, and
 * before the handler. The modules' {@link ApiOperationRules} beans name each endpoint's operation; the bound human's
 * roles come from {@link RoleGrants} in a read-only, tenant-bound transaction. Without a role directory (a slice test
 * with no database) every role-gated operation is refused: fail closed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RoleEnforcementConfiguration implements WebMvcConfigurer {

    private final ObjectProvider<ApiOperationRules> ruleSets;
    private final ObjectProvider<RoleGrants> roleGrants;
    private final ObjectProvider<PlatformTransactionManager> transactionManagers;

    RoleEnforcementConfiguration(ObjectProvider<ApiOperationRules> ruleSets, ObjectProvider<RoleGrants> roleGrants,
            ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this.ruleSets = ruleSets;
        this.roleGrants = roleGrants;
        this.transactionManagers = transactionManagers;
    }

    @Bean
    ApiOperationRules tenancyApiRules() {
        return new TenancyApiRules();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        PlatformTransactionManager manager = transactionManagers.getIfAvailable();
        TransactionOperations transactions = TransactionOperations.withoutTransaction();
        if (manager != null) {
            TransactionTemplate readOnly = new TransactionTemplate(manager);
            readOnly.setReadOnly(true);
            transactions = readOnly;
        }
        registry.addInterceptor(new RoleEnforcementInterceptor(ruleSets.orderedStream().toList(),
                        manager == null ? null : roleGrants.getIfAvailable(), transactions))
                .addPathPatterns("/api/**");
    }
}
