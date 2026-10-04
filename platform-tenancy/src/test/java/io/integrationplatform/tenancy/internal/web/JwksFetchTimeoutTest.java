package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.web.TenantFixtures.ALICE;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.AUDIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR-007 Decision 4: the JWKS fetch carries a 2 s timeout. The loopback provider answers its key set only after 6 s;
 * the request must give up after about 2 s and must not be accepted. (Retry, circuit breaker and metrics for the
 * identity provider come with the login phase - see the hand-off's Limits.)
 */
@WebMvcTest(
        controllers = TenantRowsProbeController.class,
        properties = "spring.config.import=optional:classpath:config/tenancy-security.yml")
@Import({TenancySecurityConfiguration.class, JwksFetchTimeoutTest.FakeBootstrapConfig.class})
class JwksFetchTimeoutTest {

    static final LocalIssuer ISSUER = LocalIssuer.start();

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry registry) {
        registry.add("OIDC_ISSUER_URI", ISSUER::issuer);
        registry.add("OIDC_AUDIENCE", () -> AUDIENCE);
    }

    @AfterAll
    static void stop() {
        ISSUER.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeBootstrapConfig {

        @Bean
        TenantFixtures.FakeLoginBootstrap fakeLoginBootstrap() {
            return new TenantFixtures.FakeLoginBootstrap(ISSUER.issuer());
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void aSlowKeySetFetchGivesUpAfterTwoSecondsAndAcceptsNothing() {
        ISSUER.delayKeys(Duration.ofSeconds(6));
        String token = ISSUER.rs256(ISSUER.claims(ALICE, c -> { }));

        long started = System.nanoTime();
        int status;
        try {
            status = mvc.perform(get("/api/test/rows").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus();
        } catch (Exception failure) {
            status = -1; // the provider being unreachable surfaces as a server error, never as an acceptance
        }
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(status).isNotEqualTo(200);
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(1500)).isLessThan(Duration.ofMillis(4500));
    }
}
