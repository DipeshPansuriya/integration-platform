package io.integrationplatform.config.rule.internal;

import io.integrationplatform.config.rule.adapter.out.ZenDecisionEngine;
import io.integrationplatform.config.rule.application.DecisionEngine;
import io.integrationplatform.config.rule.application.RuleEvaluationService;
import io.integrationplatform.config.rule.application.RuleSetAdminService;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.tenancy.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The composition of the rules (E6 lane B), the ConfigPersistenceConfiguration pattern: plain classes wired here, no
 * stereotype scanning of application code. One ZEN engine per application (closed at shutdown).
 */
@Configuration(proxyBeanMethods = false)
class RuleConfiguration {

    @Bean
    JdbcRuleSetRepository jdbcRuleSetRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        return new JdbcRuleSetRepository(jdbcClient, tenantContexts);
    }

    @Bean(destroyMethod = "close")
    ZenDecisionEngine zenDecisionEngine() {
        return new ZenDecisionEngine();
    }

    @Bean
    RuleSetAdminService ruleSetAdminService(JdbcRuleSetRepository ruleSets, DecisionEngine engine) {
        return new RuleSetAdminService(ruleSets, engine);
    }

    @Bean
    RuleEvaluationPort ruleEvaluationPort(JdbcRuleSetRepository ruleSets, DecisionEngine engine) {
        return new RuleEvaluationService(ruleSets, engine);
    }
}
