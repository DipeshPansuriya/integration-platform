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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
            MqTargetBody bodyPort, ObjectProvider<MqCsvTargets> csvTargets, ObjectProvider<MqCsvRows> csvRows,
            ObjectProvider<PlatformTransactionManager> transactionManager,
            ObjectProvider<CircuitBreakerRegistry> breakers, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<ObservationRegistry> observations) {
        MqOutbound plain = new MqTargetOutbound(targets::settingsOf, endpointId -> {
            try (OpenedCredentials opened = vault.open(endpointId)) {
                return opened.value(PASSWORD_FIELD).map(char[]::clone); // copy: close() zeroes the original
            }
        }, bodyPort);
        MqCsvTargets csvReader = csvTargets.getIfAvailable();
        MqCsvRows rows = csvRows.getIfAvailable();
        MqOutbound csv = csvReader == null || rows == null ? null : new MqCsvOutbound(csvReader, rows);
        MqOutbound outbound = chooseOutbound(targets::settingsOf, plain, csv);
        PlatformTransactionManager transactions = transactionManager.getIfAvailable();
        if (transactions != null) {
            outbound = inShortTransaction(outbound, transactions);
        }
        return new MqTargetCall(outbound, guard, breakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP), Duration.ofSeconds(5));
    }

    /**
     * E15/E9 (T71 for MQ): every database read of the send - settings, secret, body - happens in {@code resolve}, so
     * it runs in ONE short tenant-stamped transaction that is over before the broker is dialled. The network call
     * holds no pooled connection: a hanging queue manager must not starve the shared pool.
     */
    static MqOutbound inShortTransaction(MqOutbound outbound, PlatformTransactionManager transactions) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        return send -> tx.execute(status -> outbound.resolve(send));
    }

    /**
     * E18 finding (E15 F15 "API and MQ-CSV"): the MQ target is a CSV file on the queue (ADR-011). A stored MQ system
     * whose settings carry a {@code contract} section (the {@link JdbcMqCsvTargets} convention) sends through the CSV
     * outbound; settings without one, or without a CSV outbound composed, keep the plain transformed-body outbound,
     * which also refuses an unreadable or missing row PERMANENT.
     */
    static MqOutbound chooseOutbound(Function<UUID, Optional<String>> settingsJson, MqOutbound plain,
            MqOutbound csv) {
        if (csv == null) {
            return plain;
        }
        return send -> (carriesContract(settingsJson.apply(send.targetEndpointId())) ? csv : plain).resolve(send);
    }

    private static boolean carriesContract(Optional<String> settingsJson) {
        try {
            JsonNode contract = settingsJson.map(json -> JSON.readTree(json)).map(node -> node.get("contract")).orElse(null);
            return contract != null && contract.isObject();
        } catch (JacksonException unreadable) {
            return false;
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
}
