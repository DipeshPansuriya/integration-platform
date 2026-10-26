package io.integrationplatform.e3lanea;

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
 * Lane A's composed running system for the T-016/T-092/T-093 suites (the binding wave 0 deferred, E3 fix round 1:
 * the same composition as {@code io.integrationplatform.e3lanec.E3LaneCSuiteApp}, proven green by lane C's 10 tests
 * - one identity chain, no second shape).
 *
 * <ul>
 *   <li>Scans {@code io.integrationplatform.config} - lane B's application services and lane C's adapter/in
 *       controllers: the real APIs the suites drive.</li>
 *   <li>Scans {@code io.integrationplatform.tenancy.internal.persistence} - the stamping transaction manager (R11)
 *       and the JDBC login bootstrap.</li>
 *   <li>Scans {@code io.integrationplatform.tenancy.internal.web} and
 *       {@code io.integrationplatform.tenancy.internal.principal} - the REAL OIDC resource-server chain and its
 *       principal-realm tenant binding (ADR-007), every link production code (the ForgedTenantDenialSuiteTest
 *       pattern). The suites override only the JWT verifier with the suite's in-memory key, so the configured
 *       issuer is never contacted.</li>
 * </ul>
 *
 * <p>It lives OUTSIDE {@code io.integrationplatform.config} deliberately (the trap named in lane C's composition's
 * javadoc, which the RED run of 2026-10-05 showed): {@code E3LaneCSuiteApp} component-scans
 * {@code io.integrationplatform.config}, so a lane A configuration class left under that package would also land
 * in lane C's contexts - two suite decoder beans in one context. Lane A's plain fixtures
 * ({@code ConfigAdminApi}, {@code E3ConfigDatabase}, {@code LaneCIdentities}) stay package-visible in
 * {@code config.e3} for the suites (mirroring how lane C left its plain helpers there); this class is the only
 * lane A configuration and is registered only explicitly through {@code @SpringBootTest(classes = ...)} - the
 * wave-0 {@code ConfigE3App} it replaces sat under {@code config.e3} and is deleted, not kept alongside.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(SuiteSchedulerConfig.class)
@ComponentScan(basePackages = {
        "io.integrationplatform.config",
        "io.integrationplatform.tenancy.internal.persistence",
        "io.integrationplatform.tenancy.internal.principal",
        "io.integrationplatform.tenancy.internal.web"})
public class E3LaneASuiteApp {

    /** The suite's one signing key and decoder for every bearer of every lane A suite. */
    public static final LaneCIdentities TOKENS = new LaneCIdentities();

    /** The suite's tokens are verified against the in-memory key; the configured issuer is never contacted. */
    @Primary
    @Bean
    public JwtDecoder laneASuiteTokenDecoder() {
        return TOKENS.decoder();
    }
}
