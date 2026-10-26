package io.integrationplatform.config.rule.internal;

import io.integrationplatform.config.rule.adapter.out.ZenDecisionEngine;
import io.integrationplatform.config.rule.application.DecisionEngine;
import io.integrationplatform.config.rule.application.LookupEntryService;
import io.integrationplatform.config.rule.application.RuleEvaluationService;
import io.integrationplatform.config.rule.application.RuleSetAdminService;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.tenancy.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
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
    RuleSetAdminService ruleSetAdminService(JdbcRuleSetRepository ruleSets, DecisionEngine engine,
            ApplicationEventPublisher events, ObjectProvider<TenantContext> tenantContexts) {
        // E12-F2-S1: the edit's audit event names the bound tenant; none bound falls back fail-closed
        return new RuleSetAdminService(ruleSets, engine, events,
                tenantContexts.getIfAvailable(() -> java.util.Optional::empty));
    }

    @Bean
    RuleEvaluationPort ruleEvaluationPort(JdbcRuleSetRepository ruleSets, DecisionEngine engine,
            ObjectProvider<TenantContext> tenantContexts) {
        // L7: the compiled-decision cache is keyed by the bound tenant; none bound falls back fail-closed
        return new RuleEvaluationService(ruleSets, engine,
                tenantContexts.getIfAvailable(() -> java.util.Optional::empty));
    }

    /** E7 lane B1 (T-163): the lookup-entry edit path; the publisher arrives as the application context itself. */
    @Bean
    LookupEntryService lookupEntryService(JdbcRuleSetRepository ruleSets, DecisionEngine engine,
            ApplicationEventPublisher events) {
        return new LookupEntryService(ruleSets, engine, events);
    }
}
