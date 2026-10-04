package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.web.TenantFixtures.ALICE;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.AUDIENCE;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.TENANT_A;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADR-007 Decision 4 at lane B's level (T-001): real signature and claim validation through the configured decoder,
 * against a loopback OpenID provider (discovery + JWKS). Only the environment variable names of tenancy-security.yml
 * are set, so these tests also prove the file maps them. Every negative answers 401 and never reaches the resolver.
 */
@WebMvcTest(
        controllers = TenantRowsProbeController.class,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX, pattern = "io\\.integrationplatform\\.tenancy\\.internal\\.web\\.MeController"),
        properties = "spring.config.import=optional:classpath:config/tenancy-security.yml")
@Import({TenancySecurityConfiguration.class, TokenValidationTest.FakeBootstrapConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class TokenValidationTest {

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

    @Autowired
    private TenantFixtures.FakeLoginBootstrap bootstrap;

    private ResultActions callMe(String token) throws Exception {
        bootstrap.calls.clear();
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token));
    }

    private static Date secondsFromNow(long seconds) {
        return Date.from(Instant.now().plusSeconds(seconds));
    }

    private void rejected(String token) throws Exception {
        callMe(token).andExpect(status().isUnauthorized());
        org.assertj.core.api.Assertions.assertThat(bootstrap.calls).isEmpty();
    }

    @Test
    void anRs256TokenOfTheIssuerIsAcceptedAndBoundThroughTheBootstrap() throws Exception {
        callMe(ISSUER.rs256(ISSUER.claims(ALICE, c -> { })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()));
        org.assertj.core.api.Assertions.assertThat(bootstrap.calls).containsExactly(ISSUER.issuer() + "|" + ALICE);
    }

    @Test
    void anEs256TokenOfTheIssuerIsAccepted() throws Exception {
        callMe(ISSUER.es256(ISSUER.claims(ALICE, c -> { })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()));
    }

    @Test
    void aTokenExpiredWithinTheSixtySecondSkewIsStillAccepted() throws Exception {
        callMe(ISSUER.rs256(ISSUER.claims(ALICE, c -> c.issueTime(secondsFromNow(-330)).expirationTime(secondsFromNow(-30)))))
                .andExpect(status().isOk());
    }

    @Test
    void aWrongIssuerIsRejected() throws Exception {
        rejected(ISSUER.rs256(ISSUER.claims(ALICE, c -> c.issuer("https://other-issuer.test/realms/platform"))));
    }

    @Test
    void aMissingAudienceIsRejected() throws Exception {
        rejected(ISSUER.rs256(ISSUER.claims(ALICE, c -> c.audience((List<String>) null))));
    }

    @Test
    void anotherAudienceIsRejected() throws Exception {
        rejected(ISSUER.rs256(ISSUER.claims(ALICE, c -> c.audience("another-api"))));
    }

    @Test
    void anHs256TokenIsRejected() throws Exception {
        rejected(ISSUER.hs256(ISSUER.claims(ALICE, c -> { })));
    }

    @Test
    void anUnsignedTokenIsRejected() throws Exception {
        rejected(ISSUER.unsigned(ISSUER.claims(ALICE, c -> { })));
    }

    @Test
    void aTokenSignedWithAnUnpublishedKeyIsRejected() throws Exception {
        rejected(ISSUER.rs256WithUnknownKey(ISSUER.claims(ALICE, c -> { })));
    }

    @Test
    void aTokenExpiredBeyondTheSixtySecondSkewIsRejected() throws Exception {
        rejected(ISSUER.rs256(ISSUER.claims(ALICE, c -> c.issueTime(secondsFromNow(-420)).expirationTime(secondsFromNow(-120)))));
    }

    @Test
    void aTokenWithoutExpiryIsRejected() throws Exception {
        rejected(ISSUER.rs256(ISSUER.claims(ALICE, c -> c.expirationTime(null))));
    }

    @Test
    void aTokenNotYetValidBeyondTheSkewIsRejected() throws Exception {
        rejected(ISSUER.rs256(ISSUER.claims(ALICE, c -> c.notBeforeTime(secondsFromNow(120)))));
    }

    // E1-F1-S1 "No identity, no tenant": a token that fails validation is no identity either.

    @Test
    void anInvalidTokenWithATenantQueryGets401AndIsRecordedAsNoIdentity(CapturedOutput output) throws Exception {
        bootstrap.calls.clear();
        mvc.perform(get("/api/test/rows").header("Authorization", "Bearer not-a-jwt").queryParam("tenant", "tenant-b"))
                .andExpect(status().isUnauthorized());
        org.assertj.core.api.Assertions.assertThat(bootstrap.calls).isEmpty();
        org.assertj.core.api.Assertions.assertThat(output.getAll().lines()
                        .filter(l -> l.contains("event=tenant.denied")).toList())
                .singleElement().satisfies(line -> org.assertj.core.api.Assertions.assertThat(line)
                        .contains("channel=NO_IDENTITY", "boundTenant=null"));
        org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("tenant-b");
    }
}
