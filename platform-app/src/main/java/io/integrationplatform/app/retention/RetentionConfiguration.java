package io.integrationplatform.app.retention;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Row327 T118 (E12-F2-S3): the agreed retention figures as part of the application's configuration. */
@Configuration(proxyBeanMethods = false)
class RetentionConfiguration {

    @Bean
    RetentionPolicy retentionPolicy() {
        return RetentionPolicy.AGREED;
    }
}
