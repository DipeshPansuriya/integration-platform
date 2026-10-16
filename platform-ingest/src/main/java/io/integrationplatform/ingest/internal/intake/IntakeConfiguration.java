package io.integrationplatform.ingest.internal.intake;

import io.integrationplatform.ingest.port.IntakeTransaction;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.ingest.internal.timing.JdbcStageTimingWriter;
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
            StageTimings timings) {
        return new IntakeService(jdbcClient, tenantContext, outbox, timings);
    }

    @Bean
    StageTimings stageTimings(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcStageTimingWriter(jdbcClient, tenantContext);
    }
}