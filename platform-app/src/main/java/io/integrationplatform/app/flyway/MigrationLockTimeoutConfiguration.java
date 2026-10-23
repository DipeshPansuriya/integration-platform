package io.integrationplatform.app.flyway;

import org.flywaydb.core.api.callback.Callback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** T204: registers the lock-timeout callback; Spring Boot's Flyway auto-configuration picks up every Callback bean. */
@Configuration(proxyBeanMethods = false)
class MigrationLockTimeoutConfiguration {

    /** {@code ipaas.flyway.lock-timeout} (env {@code IPAAS_FLYWAY_LOCK_TIMEOUT}), default 5s; 0 switches it off. */
    @Bean
    Callback migrationLockTimeoutCallback(@Value("${" + MigrationLockTimeoutCallback.PROPERTY + ":5s}") String timeout) {
        return new MigrationLockTimeoutCallback(timeout);
    }
}
