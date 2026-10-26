package io.integrationplatform.e3lanec;

import io.integrationplatform.config.e3.LaneCIdentities;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import io.integrationplatform.suitesupport.SuiteSchedulerConfig;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Lane C's composed running system for the T-015/T-019/T-021 suites (the ConfigE3App pattern, one package deeper
 * in the identity chain): the smallest context that serves the real admin and lifecycle APIs AND binds the tenant
 * the way production does.
 *
 * <ul>
 *   <li>Scans {@code io.integrationplatform.config} - lane C's adapter/in controllers and application services (the
 *       APIs); lane B's schema comes through the migrations, not code.</li>
 *   <li>Scans {@code io.integrationplatform.tenancy.internal.persistence} - the stamping transaction manager (R11)
 *       and the JDBC login bootstrap.</li>
 *   <li>Scans {@code io.integrationplatform.tenancy.internal.web} and
 *       {@code io.integrationplatform.tenancy.internal.principal} - the REAL OIDC resource-server chain and its
 *       principal-realm tenant binding (ADR-007): the suites drive real signed bearers through it, the exact chain
 *       the deployed application has. The suites override only the JWT verifier with the suite's in-memory key
 *       ({@link LaneCIdentities}; the configured issuer is never contacted) - every other link of the chain,
 *       including tenant resolution, is production code. (The ForgedTenantDenialSuiteTest pattern.)</li>
 * </ul>
 *
 * <p>It lives OUTSIDE {@code io.integrationplatform.config.e3} deliberately: lane A's {@code ConfigE3App} and this
 * class both component-scan {@code io.integrationplatform.config}, so any configuration class placed under that
 * package would also be picked up into the OTHER lane's contexts (the RED run of 2026-10-05 showed three suite
 * decoder beans landing in one context through exactly that path). Plain test helpers (identities, the HTTP
 * client, the shared lifecycle steps) stay in {@code config.e3} where the package-private lane A fixtures are
 * visible; the composed app and its decoder bean are the only configuration, live here, and are registered only
 * explicitly through {@code @SpringBootTest(classes = ...)}.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(SuiteSchedulerConfig.class)
@ComponentScan(basePackages = {
        "io.integrationplatform.config",
        "io.integrationplatform.tenancy.internal.persistence",
        "io.integrationplatform.tenancy.internal.principal",
        "io.integrationplatform.tenancy.internal.web"})
public class E3LaneCSuiteApp {

    /** The suite's one signing key and decoder for every bearer of every lane C suite. */
    public static final LaneCIdentities TOKENS = new LaneCIdentities();

    /** The suite's tokens are verified against the in-memory key; the configured issuer is never contacted. */
    @Primary
    @Bean
    public JwtDecoder laneCSuiteTokenDecoder() {
        return TOKENS.decoder();
    }
}
