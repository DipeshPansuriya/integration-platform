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
 * ADR-007 Decision 4: the JWKS fetch carries a 2 s timeout. The loopback provider answers its key set only after 30 s;
 * the request must give up after about 2 s (bound 8 s, so a loaded build PC does not flake; a 10 s timeout or none
 * fails it), is not retried (a timeout of a sent request never is, R13) and is refused with 401 - never accepted and
 * never a server error. The other four controls of R13 are proved by {@link IdentityProviderResilienceTest}.
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
    void aSlowKeySetFetchGivesUpAfterTwoSecondsAndAcceptsNothing() throws Exception {
        ISSUER.delayKeys(Duration.ofSeconds(30));
        String token = ISSUER.rs256(ISSUER.claims(ALICE, c -> { }));

        long started = System.nanoTime();
        int status = mvc.perform(get("/api/test/rows").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(status).as("refused, never accepted and never a server error").isEqualTo(401);
        assertThat(ISSUER.keyRequests()).as("the timed-out fetch is not retried").isEqualTo(1);
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(1500)).isLessThan(Duration.ofSeconds(8));
    }
}
