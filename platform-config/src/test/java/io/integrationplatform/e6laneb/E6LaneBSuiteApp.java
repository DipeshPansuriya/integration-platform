package io.integrationplatform.e6laneb;

import io.integrationplatform.config.rule.RuleSuiteFixture;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * E6 lane B's composed running system for the rule-set suites (the E3LaneCSuiteApp pattern): the config module's
 * controllers, services and the rules engine, plus the REAL tenancy chain (stamping transaction manager, login
 * bootstrap, OIDC resource server, role enforcement). Only the JWT verifier is the suite's in-memory key.
 *
 * <p>It lives outside {@code io.integrationplatform.config} so the E3 suite apps' scans of that package never pick
 * up this configuration (the reason E3LaneCSuiteApp lives in its own package).
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "io.integrationplatform.config",
        "io.integrationplatform.tenancy.internal.persistence",
        "io.integrationplatform.tenancy.internal.principal",
        "io.integrationplatform.tenancy.internal.web"})
public class E6LaneBSuiteApp {

    @Primary
    @Bean
    public JwtDecoder laneBRulesSuiteTokenDecoder() {
        return RuleSuiteFixture.decoder();
    }
}
