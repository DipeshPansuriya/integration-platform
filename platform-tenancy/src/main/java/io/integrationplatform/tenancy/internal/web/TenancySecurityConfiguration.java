package io.integrationplatform.tenancy.internal.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * E1 seam placeholder (arjun-fullstack): fail closed until lane B (T-001) replaces this chain with the OIDC resource
 * server and the principal-bound tenant resolver. Only the health probe is open; every other request is denied. Lane B
 * owns this file from the moment the lanes start.
 */
@Configuration(proxyBeanMethods = false)
class TenancySecurityConfiguration {

    @Bean
    SecurityFilterChain tenancySecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
