package io.integrationplatform.tenancy.internal.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * E14 T-142: the maintenance connection, present only when {@code IPASS_MAINTENANCE_DB_URL} is set (environment
 * variable names only, R07 - the values live in the OS secret store / deployment secrets, never in a file). Without it
 * there is no {@code PartitionPurge} bean, and PAYLOAD_PURGE in DRY_RUN or ENFORCE fails by name (OFF needs none).
 */
@Configuration(proxyBeanMethods = false)
class MaintenancePurgeConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'${IPASS_MAINTENANCE_DB_URL:}'.isEmpty()")
    JdbcPartitionPurge partitionPurge(@Value("${IPASS_MAINTENANCE_DB_URL}") String url,
            @Value("${IPASS_MAINTENANCE_DB_USERNAME}") String username,
            @Value("${IPASS_MAINTENANCE_DB_PASSWORD}") String password) {
        return JdbcPartitionPurge.pooled(url, username, password);
    }
}
