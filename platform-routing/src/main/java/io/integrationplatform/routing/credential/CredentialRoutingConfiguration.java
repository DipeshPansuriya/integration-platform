package io.integrationplatform.routing.credential;

import io.integrationplatform.config.credential.api.KeyAvailability;
import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.api.TargetCredentialVault;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E5 T-185 wiring of the send step's credential gate: the configuration module's vault, key-encryption keys and
 * readiness signal, platform-delivery's store, and the application's (tenant-stamping) transaction manager. The
 * consumer that drives a claimed delivery through {@link CredentialedDelivery} before its target call is E10's (the
 * send adapters); until then the gate is composed and proven, not yet called by a route.
 */
@Configuration(proxyBeanMethods = false)
class CredentialRoutingConfiguration {

    @Bean
    SendTimeCredentials sendTimeCredentials(TargetCredentialVault vault, KeyEncryptionKeys keys) {
        return new SendTimeCredentials(vault, keys);
    }

    @Bean
    CredentialedDelivery credentialedDelivery(SendTimeCredentials credentials, KeyAvailability keyAvailability,
            DeliveryRetryStore deliveries, PlatformTransactionManager transactions,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        return new CredentialedDelivery(credentials, keyAvailability, deliveries, new TransactionTemplate(transactions),
                () -> ThreadLocalRandom.current().nextDouble(), Clock.systemUTC(),
                meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new));
    }
}
