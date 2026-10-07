package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.web.TenantFixtures.AUDIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * E4 (T-097), R13 fallback and ADR-007 Decision 4 (S-h): while the identity provider cannot be reached a console login
 * fails with a clear error - never a 500 and never a half-done login - and works again once the provider is back
 * (nothing failed is cached).
 */
@WebMvcTest(
        controllers = TenantRowsProbeController.class,
        includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "io\\.integrationplatform\\.tenancy\\.internal\\.web\\.(MeController|Console.*)"),
        properties = {
            "spring.config.import=optional:classpath:config/tenancy-security.yml,"
                    + "optional:classpath:config/tenancy-console.yml",
            "OIDC_AUDIENCE=" + AUDIENCE,
            "CONSOLE_OIDC_CLIENT_ID=platform-console",
            "CONSOLE_OIDC_REDIRECT_URI=http://localhost/login/oauth2/code/keycloak",
            "CONSOLE_POST_LOGOUT_REDIRECT_URI=http://localhost/",
            "CONSOLE_URL=/",
            // fast controls for the test; production values are in tenancy-security.yml
            "platform.tenancy.identity-provider.retry-initial-backoff=10ms",
            "platform.tenancy.identity-provider.retry-max-backoff=20ms"
        })
@Import({TenancySecurityConfiguration.class, ConsoleLoginOutageTest.Fixtures.class})
class ConsoleLoginOutageTest {

    static final LocalIssuer ISSUER = LocalIssuer.start();

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("OIDC_ISSUER_URI", ISSUER::issuer);
        registry.add("CONSOLE_OIDC_CLIENT_SECRET", () -> UUID.randomUUID().toString());
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        TenantFixtures.FakeLoginBootstrap fakeLoginBootstrap() {
            return new TenantFixtures.FakeLoginBootstrap(ISSUER.issuer());
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void aLoginWhileTheProviderIsDownIsAClearErrorAndWorksAgainAfterItIsBack() throws Exception {
        ISSUER.discoveryStatus(503);

        MockHttpServletResponse down = mvc.perform(get("/oauth2/authorization/keycloak")).andReturn().getResponse();

        assertThat(down.getStatus()).as("a clear unavailable answer, never a 500").isEqualTo(503);
        assertThat(down.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(down.getContentAsString()).contains("identity_provider_unavailable");
        assertThat(down.getRedirectedUrl()).isNull();

        ISSUER.discoveryStatus(200);
        MockHttpServletResponse back = mvc.perform(get("/oauth2/authorization/keycloak")).andReturn().getResponse();

        assertThat(back.getStatus()).isEqualTo(302);
        assertThat(back.getRedirectedUrl()).startsWith(ISSUER.issuer() + "/protocol/openid-connect/auth?");
    }
}
