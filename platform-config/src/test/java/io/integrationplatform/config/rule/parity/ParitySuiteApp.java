package io.integrationplatform.config.rule.parity;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * T-029's composed running system (the E6LaneBSuiteApp pattern): the config module's controllers, services and the
 * rules engine exactly as production composes them, plus the REAL tenancy chain (stamping transaction manager, login
 * bootstrap, OIDC resource server, role enforcement). Only the JWT verifier is the suite's in-memory key.
 *
 * <p>Deliberately carries NO stereotype ({@code @Configuration} / {@code @SpringBootConfiguration}): it lives inside
 * {@code io.integrationplatform.config}, which the E3 and E6 lane B suite apps component-scan, and an unannotated class
 * is never picked up by their scans. It is registered only where a test names it in {@code @SpringBootTest(classes)}.
 */
@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "io.integrationplatform.config",
        "io.integrationplatform.tenancy.internal.persistence",
        "io.integrationplatform.tenancy.internal.principal",
        "io.integrationplatform.tenancy.internal.web"})
public class ParitySuiteApp {

    @Primary
    @Bean
    public JwtDecoder paritySuiteTokenDecoder() {
        return ParityFixture.decoder();
    }
}
