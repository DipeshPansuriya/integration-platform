package io.integrationplatform.config.e5.system;

import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.destination.SocketDialer;
import io.integrationplatform.config.e5.destination.WireMockDestination;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * E5 lane B's composed running system (the E3LaneCSuiteApp composition: the config module's real controllers and
 * services, the stamping transaction manager and login bootstrap, and the REAL OIDC resource-server chain with its
 * role interceptor). Three things are the suite's own, everything else is production code:
 *
 * <ul>
 *   <li>the JWT verification key ({@link E5SystemFixture#decoder()}; the configured issuer is never contacted);</li>
 *   <li>the destination guard's two ports - {@link HostResolver} ("DNS": WireMock's test name and the refused-range
 *       names) and {@link SocketDialer} (connects only the pinned documentation address to WireMock); the guard and
 *       the HTTP check themselves are the production beans;</li>
 *   <li>a {@link SimpleMeterRegistry}, so the suite reads the metrics the check records.</li>
 * </ul>
 *
 * <p>Deliberately NOT a {@code @Configuration} (only {@code @EnableAutoConfiguration} + {@code @ComponentScan}, a lite
 * configuration class registered through {@code @SpringBootTest(classes = ...)}): the E3 suite apps component-scan
 * {@code io.integrationplatform.config}, and a stereotype here would land in their contexts too (the trap named in
 * E3LaneCSuiteApp's javadoc). Without a stereotype annotation the scan never picks this class up.
 */
@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "io.integrationplatform.config",
        "io.integrationplatform.tenancy.internal.persistence",
        "io.integrationplatform.tenancy.internal.principal",
        "io.integrationplatform.tenancy.internal.web"})
public class E5SystemSuiteApp {

    /** The one WireMock of the JVM's lane B suites; started by the suites that need it. */
    static final WireMockDestination WIREMOCK = new WireMockDestination();

    @Primary
    @Bean
    JwtDecoder e5SystemSuiteTokenDecoder() {
        return E5SystemFixture.decoder();
    }

    @Primary
    @Bean
    HostResolver e5SystemSuiteResolver() {
        return WIREMOCK.resolver();
    }

    @Primary
    @Bean
    SocketDialer e5SystemSuiteDialer() {
        return WIREMOCK.dialer();
    }

    @Primary
    @Bean
    MeterRegistry e5SystemSuiteMeters() {
        return new SimpleMeterRegistry();
    }
}
