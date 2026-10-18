package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.MqConnectionCheck;
import io.integrationplatform.config.destination.SocketDialer;
import java.time.Duration;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-126 wiring: the MQ adapter answers config's test-connection port. The settings come from the row config already
 * read in its tenant-bound transaction, so nothing is re-read here; a malformed row is a BAD_SETTINGS answer.
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
}
