package io.integrationplatform.routing.adapter.mq;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.routing.credential.OpenedCredentials;
import io.integrationplatform.routing.credential.SendTimeCredentials;
import io.integrationplatform.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Row 327: the production MQ target call - stored MQ settings, the vault's queue-manager password, the body port.
 * Wired with the pipeline (same property gate as {@code PipelineCompositionConfiguration}), which picks it by type.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_KAFKA_BOOTSTRAP_SERVERS")
public class MqTargetCallConfiguration {

    /** The vault stores the field under its constant name ({@code sealed.field()}), not its admin JSON name. */
    private static final String PASSWORD_FIELD = "QUEUE_MANAGER_PASSWORD";

    @Bean
    JdbcMqTargets mqTargets(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcMqTargets(jdbcClient, tenantContext);
    }

    @Bean
    MqTargetCall mqTargetCall(JdbcMqTargets targets, SendTimeCredentials vault, DestinationGuard guard,
            MqTargetBody bodyPort, ObjectProvider<CircuitBreakerRegistry> breakers,
            ObjectProvider<MeterRegistry> meters, ObjectProvider<ObservationRegistry> observations) {
        MqOutbound outbound = new MqTargetOutbound(targets::settingsOf, endpointId -> {
            try (OpenedCredentials opened = vault.open(endpointId)) {
                return opened.value(PASSWORD_FIELD).map(char[]::clone); // copy: close() zeroes the original
            }
        }, bodyPort);
        return new MqTargetCall(outbound, guard, breakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP), Duration.ofSeconds(5));
    }
}
