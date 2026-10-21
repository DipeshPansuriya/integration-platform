package io.integrationplatform.config.credential.readiness;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** T-201 (E10-F15): exposes the key health check as this instance's readiness. */
@Configuration(proxyBeanMethods = false)
class InstanceKeyReadinessConfiguration {

    @Bean
    InstanceKeyReadiness instanceKeyReadiness(TargetCredentialKeysHealthIndicator targetCredentialKeysHealthIndicator) {
        return new InstanceKeyReadiness(targetCredentialKeysHealthIndicator);
    }
}
