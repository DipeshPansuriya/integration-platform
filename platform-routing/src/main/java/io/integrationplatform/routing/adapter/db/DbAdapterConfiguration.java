package io.integrationplatform.routing.adapter.db;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.MqConnectionCheck;
import io.integrationplatform.config.destination.SocketDialer;
import io.integrationplatform.routing.credential.OpenedCredentials;
import io.integrationplatform.routing.credential.SendTimeCredentials;
import io.integrationplatform.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-149 wiring: the DB adapter's beans - the "DB" test-connection check (the checks-map shape the MQ adapter
 * answers too) and the production target call. Gated by its own property so no existing context changes until
 * the lead wires the two register() seams and flips the gate the way {@code KafkaAdapterConfiguration} is wired.
 *
 * <p>E16 security: {@code platform.config.destinations.db-allowed-ports} (default 5432) lists the database ports a
 * target may use; {@code platform.config.destinations.db-sslmode} (default {@code require}) is the TLS mode of a
 * target that names none.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_DB_TARGET_ENABLED")
public class DbAdapterConfiguration {

    @Bean
    DbTargets jdbcDbTargets(JdbcClient jdbcClient, TenantContext tenantContext,
            ObjectProvider<SendTimeCredentials> vault) {
        SendTimeCredentials credentials = vault.getIfAvailable();
        if (credentials == null) {
            return null; // no credential slice composed: the reader is not wired (nothing calls it yet either)
        }
        return new JdbcDbTargets(jdbcClient, tenantContext, endpointId -> {
            try (OpenedCredentials opened = credentials.open(endpointId)) {
                return opened.value(JdbcDbTargets.DB_ACCOUNT_PASSWORD_FIELD).map(char[]::clone);
                // copy: close() zeroes the original
            }
        });
    }

    @Bean
    MqConnectionCheck dbConnectionCheck(DestinationGuard guard, SocketDialer dialer) {
        DbBrokerProbe probe = new SocketDbBrokerProbe(guard, dialer);
        return (systemId, settingsJson) -> new DbTestConnection(guard, probe, Duration.ofSeconds(5))
                .test(systemId, settingsJson);
    }

    @Bean
    DbSender dbSender(@Value("${platform.config.destinations.db-sslmode:require}") String sslmode) {
        return new JdbcDbSender(Duration.ofSeconds(5), sslmode);
    }

    @Bean
    DbOutbound dbOutbound(DbTargets targets, ObjectProvider<DbRows> rows) {
        DbRows rowsPort = rows.getIfAvailable();
        if (rowsPort == null) {
            return null; // the rows port composes with the transform wiring (lead integration, the MqCsvRows seam)
        }
        return new DbTargetOutbound(targets, rowsPort);
    }

    /** E16 F3: the send-time and publish-time reads of the tenant's approved tables (one JDBC adapter, both ports). */
    @Bean
    JdbcApprovedTables jdbcApprovedTables(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcApprovedTables(jdbcClient, tenantContext);
    }

    /** E16 F3 (task 10): an interface whose DB table is not approved cannot be published (ADR-017). */
    @Bean
    DbTablePublishListener dbTablePublishListener(JdbcApprovedTables approved) {
        return new DbTablePublishListener(approved);
    }

    @Bean
    DbTargetCall dbTargetCall(ApprovedTables approvedTables, DbOutbound outbound, DbSender sender, DestinationGuard guard,
            @Value("${platform.config.destinations.db-allowed-ports:5432}") java.util.List<Integer> allowedPorts,
            ObjectProvider<CircuitBreakerRegistry> breakers, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<ObservationRegistry> observations) {
        return new DbTargetCall(approvedTables, outbound, sender, guard, allowedPorts, breakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }
}