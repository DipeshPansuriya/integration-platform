package io.integrationplatform.ingest.internal.intake;

import io.integrationplatform.ingest.adapter.in.CorrelationFilter;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.ingest.port.IntakeTransaction;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.ingest.internal.timing.JdbcStageTimingWriter;
import io.integrationplatform.ingest.validation.ContractSchemaCheck;
import io.integrationplatform.ingest.validation.PayloadValidationService;
import io.integrationplatform.tenancy.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-048 wiring: the controller calls the frozen port {@link IntakeTransaction}; this is its only implementation.
 * The outbox writer and the stage timing writer are the other two frozen ports - all three take the tenant from
 * the request-bound {@link TenantContext}, never from a parameter (R08/R11).
 */
@Configuration(proxyBeanMethods = false)
public class IntakeConfiguration {

    @Bean
    IntakeTransaction intakeTransaction(JdbcClient jdbcClient, TenantContext tenantContext, OutboxWriter outbox,
            StageTimings timings, PayloadValidationService payloadValidation) {
        return new IntakeService(jdbcClient, tenantContext, outbox, timings, payloadValidation);
    }

    /**
     * B37 (E6-F2-S1, T-027): the contract-then-rules validation the intake runs on every record. The schema check
     * is one shared instance so its compiled-schema cache (per tenant and contract version) is shared by all requests;
     * the rules go through the configuration module's public port - the same engine the test panel uses.
     */
    @Bean
    PayloadValidationService payloadValidationService(RuleEvaluationPort rules) {
        return new PayloadValidationService(new ContractSchemaCheck(), rules);
    }

    /** T-063 (row 278 T107): every request runs in its journey - Boot registers this filter for all URLs. */
    @Bean
    CorrelationFilter correlationFilter() {
        return new CorrelationFilter();
    }

    @Bean
    StageTimings stageTimings(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcStageTimingWriter(jdbcClient, tenantContext);
    }
}