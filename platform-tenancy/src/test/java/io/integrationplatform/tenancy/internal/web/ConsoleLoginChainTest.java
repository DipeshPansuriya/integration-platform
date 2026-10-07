package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.web.TenantFixtures.ALICE;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.AUDIENCE;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.CAROL;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * E4-F1-S1 (T-097), ADR-007 Decision 1 - the console's backend-for-frontend chain without Docker: a session that
 * oauth2Login left behind (security context + authorized client in the HTTP session) is turned into the SAME
 * tenant-from-identity path as a bearer token (decoder, login bootstrap, tenant forgery checks). The real Keycloak
 * round trip runs in platform-app's ConsoleLoginContainerTest.
 */
@WebMvcTest(
        controllers = TenantRowsProbeController.class,
        includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "io\\.integrationplatform\\.tenancy\\.internal\\.web\\.(MeController|Console.*)"),
        properties = {
            "spring.config.import=optional:classpath:config/tenancy-security.yml,"
                    + "optional:classpath:config/tenancy-console.yml",
            "OIDC_AUDIENCE=" + AUDIENCE,
            "CONSOLE_OIDC_CLIENT_ID=" + ConsoleLoginChainTest.CLIENT_ID,
            "CONSOLE_OIDC_REDIRECT_URI=" + ConsoleLoginChainTest.REDIRECT_URI,
            "CONSOLE_POST_LOGOUT_REDIRECT_URI=" + ConsoleLoginChainTest.POST_LOGOUT_URI,
            "CONSOLE_URL=/"
        })
@Import({TenancySecurityConfiguration.class, ConsoleLoginChainTest.Fixtures.class})
@ExtendWith(OutputCaptureExtension.class)
class ConsoleLoginChainTest {

    static final String CLIENT_ID = "platform-console";
    static final String REDIRECT_URI = "http://localhost/login/oauth2/code/keycloak";
    static final String POST_LOGOUT_URI = "http://localhost/signed-out";
    static final String SESSION_COOKIE = "SESSION";
    static final LocalIssuer ISSUER = LocalIssuer.start();
    private static final String CLIENT_SECRET = UUID.randomUUID().toString(); // random per run (R07)

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("OIDC_ISSUER_URI", ISSUER::issuer);
        registry.add("CONSOLE_OIDC_CLIENT_SECRET", () -> CLIENT_SECRET);
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

    @Autowired
    private TenantFixtures.FakeLoginBootstrap bootstrap;

    @BeforeEach
    void reset() {
        bootstrap.calls.clear();
        TenantRowsProbeController.READS.set(0);
    }

    // ---- the session becomes the same bound identity as a bearer token -------------------------------------------

    @Test
    void aSessionCallIsAnsweredForTheTenantBoundFromTheSessionsAccessToken() throws Exception {
        MvcResult me = mvc.perform(get("/api/v1/me").session(signedIn(ALICE)).cookie(sessionCookie())).andReturn();

        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(me.getResponse().getContentAsString()).contains(TENANT_A.toString());
        assertThat(bootstrap.calls).containsExactly(ISSUER.issuer() + "|" + ALICE);
    }

    @Test
    void theBrowserNeverReceivesTheAccessTokenInABodyOrACookie() throws Exception {
        MockHttpSession session = signedIn(ALICE);
        List<MockHttpServletResponse> responses = List.of(
                mvc.perform(get("/api/v1/me").session(session).cookie(sessionCookie())).andReturn().getResponse(),
                mvc.perform(get("/api/test/rows").session(session).cookie(sessionCookie())).andReturn().getResponse());
        for (MockHttpServletResponse response : responses) {
            assertThat(response.getContentAsString()).doesNotContain("eyJ");
            assertThat(response.getHeaders("Set-Cookie")).noneMatch(cookie -> cookie.contains("eyJ"));
            assertThat(response.getHeaderNames()).doesNotContain("Authorization");
        }
    }

    @Test
    void aTenantClaimNamingAnotherTenantIsDeniedAndRecordedAsTokenClaim(CapturedOutput output) throws Exception {
        MockHttpSession forged = signedIn(ALICE, claims -> claims.claim("tenant", "tenant-b"));

        int status = mvc.perform(get("/api/v1/me").session(forged).cookie(sessionCookie()))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
        assertThat(output.getAll().lines().filter(l -> l.contains("event=tenant.denied")).toList())
                .anyMatch(l -> l.contains("channel=TOKEN_CLAIM") && l.contains(TENANT_A.toString()));
        assertThat(TenantRowsProbeController.READS).hasValue(0);
    }

    @Test
    void aSessionWhoseUserTheBootstrapDoesNotResolveIsDenied() throws Exception {
        int status = mvc.perform(get("/api/v1/me").session(signedIn(CAROL)).cookie(sessionCookie()))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
        assertThat(bootstrap.calls).containsExactly(ISSUER.issuer() + "|" + CAROL);
    }

    @Test
    void aSessionWhoseAccessTokenExpiredAndCannotBeRefreshedIsAJson401() throws Exception {
        Instant past = Instant.now().minusSeconds(600);
        MockHttpSession expired = signedIn(ALICE, claims -> claims.issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(300))), past, past.plusSeconds(300));

        MockHttpServletResponse response =
                mvc.perform(get("/api/v1/me").session(expired).cookie(sessionCookie())).andReturn().getResponse();

        assertJson401(response);
        assertThat(bootstrap.calls).isEmpty();
    }

    // ---- no session, no data: 401 JSON for the API, never a redirect ------------------------------------------------

    @Test
    void aSessionCookieWithoutALiveSessionIsAJson401AndNeverARedirect() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/api/v1/me").cookie(sessionCookie())).andReturn().getResponse();

        assertJson401(response);
    }

    @Test
    void aStateChangingCallWithADeadSessionIsAJson401NotACsrf403() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/test/rows/search").cookie(sessionCookie())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse();

        assertJson401(response);
    }

    @Test
    void aBearerCallCarryingASessionCookieStillTakesTheBearerChain() throws Exception {
        String token = ISSUER.rs256(ISSUER.claims(ALICE, claims -> { }));

        int status = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token).cookie(sessionCookie()))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
    }

    // ---- CSRF on state-changing session requests --------------------------------------------------------------------

    @Test
    void aStateChangingSessionCallWithoutTheCsrfHeaderIs403() throws Exception {
        MockHttpSession session = signedIn(ALICE);
        String xsrf = xsrfCookie(session);

        int status = mvc.perform(post("/api/test/rows/search").session(session)
                        .cookie(sessionCookie(), new Cookie("XSRF-TOKEN", xsrf))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
        assertThat(TenantRowsProbeController.READS).hasValue(0);
    }

    @Test
    void aStateChangingSessionCallWithTheCsrfHeaderPassesTheCsrfCheck() throws Exception {
        MockHttpSession session = signedIn(ALICE);
        String xsrf = xsrfCookie(session);

        int status = mvc.perform(post("/api/test/rows/search").session(session)
                        .cookie(sessionCookie(), new Cookie("XSRF-TOKEN", xsrf)).header("X-XSRF-TOKEN", xsrf)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void theCsrfCookieIsScriptReadableButCarriesNoToken() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/me").session(signedIn(ALICE))
                .cookie(sessionCookie())).andReturn().getResponse();

        Cookie xsrf = response.getCookie("XSRF-TOKEN");
        assertThat(xsrf).as("the CSRF cookie the console echoes in X-XSRF-TOKEN").isNotNull();
        assertThat(xsrf.isHttpOnly()).isFalse();
        assertThat(xsrf.getValue()).doesNotContain("eyJ");
    }

    // ---- login and logout endpoints ---------------------------------------------------------------------------------

    @Test
    void theLoginEntryRedirectsToTheIdentityProviderWithPkceS256() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/oauth2/authorization/keycloak")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(302);
        URI location = URI.create(response.getRedirectedUrl());
        assertThat(location.toString()).startsWith(ISSUER.issuer() + "/protocol/openid-connect/auth?");
        Map<String, String> query = UriComponentsBuilder.fromUri(location).build().getQueryParams().toSingleValueMap();
        assertThat(query).containsEntry("response_type", "code")
                .containsEntry("client_id", CLIENT_ID)
                .containsEntry("code_challenge_method", "S256")
                .containsKey("code_challenge")
                .containsKey("state");
        assertThat(query.get("redirect_uri")).isIn(REDIRECT_URI,
                java.net.URLEncoder.encode(REDIRECT_URI, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(query.get("scope")).contains("openid");
        assertThat(location.toString()).doesNotContain(CLIENT_SECRET);
    }

    @Test
    void logoutWithoutTheCsrfHeaderIs403() throws Exception {
        MockHttpSession session = signedIn(ALICE);
        String xsrf = xsrfCookie(session);

        int status = mvc.perform(post("/logout").session(session)
                        .cookie(sessionCookie(), new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    @Test
    void logoutEndsTheSessionAndAnswersTheIdentityProviderSignOutAddressAsJson() throws Exception {
        MockHttpSession session = signedIn(ALICE);
        String xsrf = xsrfCookie(session);

        MockHttpServletResponse response = mvc.perform(post("/logout").session(session)
                        .cookie(sessionCookie(), new Cookie("XSRF-TOKEN", xsrf)).header("X-XSRF-TOKEN", xsrf))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString())
                .contains(ISSUER.issuer() + "/protocol/openid-connect/logout")
                .contains("post_logout_redirect_uri")
                .doesNotContain("eyJ");
        assertThat(session.isInvalid()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static void assertJson401(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl()).as("never a redirect for the API").isNull();
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"error\"");
    }

    private static Cookie sessionCookie() {
        return new Cookie(SESSION_COOKIE, "opaque-session-id");
    }

    /** The CSRF cookie the chain hands out on a console GET (the console echoes it in X-XSRF-TOKEN). */
    private String xsrfCookie(MockHttpSession session) throws Exception {
        Cookie cookie = mvc.perform(get("/api/v1/me").session(session).cookie(sessionCookie()))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN cookie").isNotNull();
        return cookie.getValue();
    }

    private static MockHttpSession signedIn(String subject) {
        return signedIn(subject, claims -> { });
    }

    private static MockHttpSession signedIn(String subject, Consumer<JWTClaimsSet.Builder> claims) {
        Instant now = Instant.now();
        return signedIn(subject, claims, now, now.plusSeconds(300));
    }

    /** What oauth2Login leaves in the session after a successful login: the security context and the client. */
    private static MockHttpSession signedIn(String subject, Consumer<JWTClaimsSet.Builder> claims, Instant issuedAt,
            Instant expiresAt) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("keycloak")
                .clientId(CLIENT_ID)
                .clientSecret(CLIENT_SECRET)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI)
                .scope("openid")
                .authorizationUri(ISSUER.issuer() + "/protocol/openid-connect/auth")
                .tokenUri(ISSUER.issuer() + "/protocol/openid-connect/token")
                .jwkSetUri(ISSUER.issuer() + "/protocol/openid-connect/certs")
                .issuerUri(ISSUER.issuer())
                .userNameAttributeName("sub")
                .providerConfigurationMetadata(
                        Map.of("end_session_endpoint", ISSUER.issuer() + "/protocol/openid-connect/logout"))
                .build();
        String accessToken = ISSUER.rs256(ISSUER.claims(subject, claims));
        OidcIdToken idToken = new OidcIdToken("id-token-of-" + subject, issuedAt, expiresAt,
                Map.of("sub", subject, "iss", ISSUER.issuer()));
        OAuth2AuthenticationToken authentication =
                new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(), idToken), List.of(), "keycloak");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        new HttpSessionOAuth2AuthorizedClientRepository().saveAuthorizedClient(
                new OAuth2AuthorizedClient(registration, subject,
                        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, accessToken, issuedAt, expiresAt)),
                authentication, request, new MockHttpServletResponse());
        return session;
    }
}
