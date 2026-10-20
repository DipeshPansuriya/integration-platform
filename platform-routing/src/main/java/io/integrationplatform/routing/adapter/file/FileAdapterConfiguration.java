package io.integrationplatform.routing.adapter.file;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.MqConnectionCheck;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-150: the File adapter's beans - the "FILE" test-connection check (same MqConnectionCheck interface the
 * checks map holds) and the production target call. Gated by its own property so no existing context changes
 * until the lead wires the checks map and the TargetCallRegistry entry; the gate flips with the integration.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_FILE_TARGET_ENABLED")
public class FileAdapterConfiguration {

    @Bean
    MqConnectionCheck fileConnectionCheck() {
        return (systemId, settingsJson) -> new FileTestConnection(id -> Optional.of(FileSettings.fromJson(settingsJson)))
                .test(systemId);
    }

    @Bean
    FileTargetCall fileTargetCall(FileOutbound outbound, ObjectProvider<CircuitBreakerRegistry> breakers,
            ObjectProvider<MeterRegistry> meters, ObjectProvider<ObservationRegistry> observations) {
        return new FileTargetCall(outbound,
                breakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }
}