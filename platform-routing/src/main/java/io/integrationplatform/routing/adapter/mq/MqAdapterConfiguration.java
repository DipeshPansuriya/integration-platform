package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.MqConnectionCheck;
import io.integrationplatform.config.destination.SocketDialer;
import io.integrationplatform.routing.credential.OpenedCredentials;
import io.integrationplatform.routing.credential.SendTimeCredentials;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-126 wiring: the MQ adapter answers config's test-connection port. The settings come from the row config already
 * read in its tenant-bound transaction, so nothing is re-read here; a malformed row is a BAD_SETTINGS answer.
 * T-125 (E10-S2): the MQ-CSV reader is wired here too so {@link MqCsvOutbound} resolves from the target's STORED
 * registry settings - the credential seam supplies the queue-manager password (opened only at send time; the copy
 * cloned here is the one the send path zeroes), never a value from these rows.
 */
@Configuration(proxyBeanMethods = false)
public class MqAdapterConfiguration {

    @Bean
    MqConnectionCheck mqConnectionCheck(DestinationGuard guard, SocketDialer dialer) {
        BrokerProbe probe = new SocketBrokerProbe(guard, dialer);
        return (systemId, settingsJson) -> new MqTestConnection(
                id -> Optional.of(MqSettings.fromJson(settingsJson)), guard, probe, Duration.ofSeconds(5))
                .test(systemId);
    }

    @Bean
    MqCsvTargets mqCsvTargets(JdbcClient jdbcClient, TenantContext tenantContext,
            ObjectProvider<SendTimeCredentials> vault) {
        SendTimeCredentials credentials = vault.getIfAvailable();
        if (credentials == null) {
            return null; // no credential slice composed: the MQ-CSV reader is not wired (nothing calls it yet either)
        }
        return new JdbcMqCsvTargets(jdbcClient, tenantContext, endpointId -> {
            try (OpenedCredentials opened = credentials.open(endpointId)) {
                return opened.value(JdbcMqCsvTargets.QUEUE_MANAGER_PASSWORD_FIELD).map(char[]::clone);
                // copy: close() zeroes the original
            }
        });
    }
}