package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.web.TenantFixtures.ALICE;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.AUDIENCE;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.BOB;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.CAROL;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.TENANT_A;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.TENANT_B;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.USER_ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.integrationplatform.tenancy.TenantContext;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * E1-F1-S1 "A forged tenant changes nothing" at lane B's level (T-001): MockMvc, spring-security-test {@code jwt()} and
 * a fake login bootstrap with a user of tenant A and a user of tenant B. Lane D proves the same end to end on
 * PostgreSQL. Also E1-F2-S3 (resolver half): the tenant comes from the login bootstrap for the token's (issuer,
 * subject), never from a claim, header, body or path.
 */
@WebMvcTest(
        controllers = TenantRowsProbeController.class,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX, pattern = "io\\.integrationplatform\\.tenancy\\.internal\\.web\\.MeController"),
        properties = {
            "spring.config.import=optional:classpath:config/tenancy-security.yml",
            "OIDC_ISSUER_URI=" + TenantFromIdentityOnlyTest.ISSUER,
            "OIDC_AUDIENCE=" + AUDIENCE
        })
@Import({TenancySecurityConfiguration.class, TenantFromIdentityOnlyTest.FakeBootstrapConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class TenantFromIdentityOnlyTest {

    /** Never contacted: {@code jwt()} puts an already-validated token in the security context. */
    static final String ISSUER = "http://127.0.0.1:9/realms/platform";

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeBootstrapConfig {

        @Bean
        TenantFixtures.FakeLoginBootstrap fakeLoginBootstrap() {
            return new TenantFixtures.FakeLoginBootstrap(ISSUER);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantFixtures.FakeLoginBootstrap bootstrap;

    @Autowired
    private ObjectProvider<TenantContext> tenantContext;

    @BeforeEach
    void reset() {
        bootstrap.calls.clear();
        TenantRowsProbeController.READS.set(0);
    }

    private static RequestPostProcessor signedIn(String subject) {
        return signedIn(subject, b -> { });
    }

    private static RequestPostProcessor signedIn(String subject, Consumer<Jwt.Builder> extra) {
        return jwt().jwt(b -> {
            b.claim("iss", ISSUER).subject(subject).audience(List.of(AUDIENCE));
            extra.accept(b);
        });
    }

    private static List<String> denials(CapturedOutput output) {
        return output.getAll().lines().filter(l -> l.contains("event=tenant.denied")).toList();
    }

    // Scenario: A tenant-A user sees only tenant-A rows

    @Test
    void aTenantAUserSeesOnlyTenantARows() throws Exception {
        mvc.perform(get("/api/test/rows").with(signedIn(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].tenantId", everyItem(is(TENANT_A.toString()))));
        assertThat(bootstrap.calls).containsExactly(ISSUER + "|" + ALICE);
    }

    @Test
    void theMeEndpointAnswersTheBoundUserAndTenantOnly() throws Exception {
        mvc.perform(get("/api/v1/me").with(signedIn(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ALICE.toString()))
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()))
                .andExpect(jsonPath("$.tenantCode").value("tenant-a"))
                .andExpect(jsonPath("$.timeZone").value("Europe/Berlin"));
    }

    @Test
    void aTenantBUserSeesOnlyTenantBRows() throws Exception {
        mvc.perform(get("/api/test/rows").with(signedIn(BOB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tenantId").value(TENANT_B.toString()));
    }

    @Test
    void nothingOfTheBindingSurvivesTheRequest() throws Exception {
        mvc.perform(get("/api/test/rows").with(signedIn(ALICE))).andExpect(status().isOk());
        assertThat(tenantContext.getObject().currentTenantId()).isEmpty();
    }

    // Scenario Outline: A forged tenant is ignored

    @Test
    void aForgedTenantHeaderIsIgnoredAndRecorded(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/test/rows").with(signedIn(ALICE)).header("Tenant", TENANT_B.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].tenantId", everyItem(is(TENANT_A.toString()))));
        assertThat(denials(output)).singleElement().satisfies(line -> assertThat(line)
                .contains("channel=HEADER", "method=GET", "path=/api/test/rows", "boundTenant=" + TENANT_A));
        assertThat(output.getAll()).doesNotContain(TENANT_B.toString());
    }

    @Test
    void anyHeaderNamingATenantIsChecked(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/me").with(signedIn(ALICE)).header("X-Tenant-Id", "tenant-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()));
        assertThat(denials(output)).singleElement().satisfies(line -> assertThat(line).contains("channel=HEADER"));
        assertThat(output.getAll()).doesNotContain("tenant-b");
    }

    @Test
    void aForgedTenantIdBodyFieldIsIgnoredAndRecorded(CapturedOutput output) throws Exception {
        String body = "{\"name\":\"probe\",\"filter\":{\"tenantId\":\"" + TENANT_B + "\"}}";
        mvc.perform(post("/api/test/rows/search").with(signedIn(ALICE))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[*].tenantId", everyItem(is(TENANT_A.toString()))))
                .andExpect(jsonPath("$.echoName").value("probe"));
        assertThat(denials(output)).singleElement().satisfies(line -> assertThat(line)
                .contains("channel=BODY_FIELD", "method=POST", "path=/api/test/rows/search"));
        assertThat(output.getAll()).doesNotContain(TENANT_B.toString());
    }

    @Test
    void aForgedTenantIdQueryParameterIsIgnoredAndRecorded(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/test/rows").with(signedIn(ALICE)).queryParam("tenantId", TENANT_B.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].tenantId", everyItem(is(TENANT_A.toString()))));
        assertThat(denials(output)).singleElement().satisfies(line -> assertThat(line)
                .contains("channel=QUERY_PARAMETER", "path=/api/test/rows", "boundTenant=" + TENANT_A));
        assertThat(output.getAll()).doesNotContain(TENANT_B.toString());
    }

    @Test
    void aTenantBRecordIdInThePathAnswers404AndIsRecorded(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/test/rows/{id}", TenantRowsProbeController.B1.id()).with(signedIn(ALICE)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/test/rows/{id}", TenantRowsProbeController.A1.id()).with(signedIn(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()));
        assertThat(denials(output)).singleElement().satisfies(line -> assertThat(line)
                .contains("channel=PATH_ID", "path=/api/test/rows/{id}", "boundTenant=" + TENANT_A));
        assertThat(output.getAll()).doesNotContain(TenantRowsProbeController.B1.id().toString());
    }

    @Test
    void namingTheOwnTenantIsNoForgery(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/test/rows").with(signedIn(ALICE))
                        .header("Tenant", TENANT_A.toString()).queryParam("tenant", "tenant-a"))
                .andExpect(status().isOk());
        assertThat(denials(output)).isEmpty();
    }

    // Scenario: No identity, no tenant

    @Test
    void anUnauthenticatedRequestWithATenantHeaderGets401AndReadsNothing(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/test/rows").header("Tenant", TENANT_B.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
        assertThat(bootstrap.calls).isEmpty();
        assertThat(TenantRowsProbeController.READS).hasValue(0);
        assertThat(denials(output)).singleElement().satisfies(line -> assertThat(line)
                .contains("channel=NO_IDENTITY", "path=/api/test/rows", "boundTenant=null"));
        assertThat(output.getAll()).doesNotContain(TENANT_B.toString());
    }

    @Test
    void anUnauthenticatedRequestWithATenantBodyFieldGets401AndReadsNothing(CapturedOutput output)
            throws Exception {
        mvc.perform(post("/api/test/rows/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"" + TENANT_B + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(bootstrap.calls).isEmpty();
        assertThat(TenantRowsProbeController.READS).hasValue(0);
        assertThat(denials(output)).singleElement().satisfies(line -> assertThat(line)
                .contains("channel=NO_IDENTITY", "boundTenant=null"));
    }

    @Test
    void anUnauthenticatedRequestWithAnInvalidTokenAndATenantQueryGets401AndIsRecorded(CapturedOutput output)
            throws Exception {
        mvc.perform(get("/api/test/rows").header("Authorization", "Bearer not-a-jwt")
                        .queryParam("tenant", "tenant-b"))
                .andExpect(status().isUnauthorized());
        assertThat(bootstrap.calls).isEmpty();
        assertThat(TenantRowsProbeController.READS).hasValue(0);
        assertThat(denials(output)).singleElement().satisfies(line -> assertThat(line)
                .contains("channel=NO_IDENTITY"));
    }

    @Test
    void anUnauthenticatedRequestWithoutATenantFieldGets401AndIsNotRecorded(CapturedOutput output)
            throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        assertThat(denials(output)).isEmpty();
    }

    // E1-F2-S3, resolver half

    @Test
    void aValidTokenWithoutAnActiveBindingGets403AndNoTenantData() throws Exception {
        MvcResult result = mvc.perform(get("/api/test/rows").with(signedIn(CAROL)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(bootstrap.calls).containsExactly(ISSUER + "|" + CAROL);
        assertThat(TenantRowsProbeController.READS).hasValue(0);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("tenant");
    }

    @Test
    void aTokenTenantClaimNamingAnotherTenantGets403AndIsRecorded(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/test/rows").with(signedIn(ALICE, b -> b.claim("tenant", TENANT_B.toString()))))
                .andExpect(status().isForbidden());
        assertThat(TenantRowsProbeController.READS).hasValue(0);
        assertThat(denials(output)).singleElement().satisfies(line -> assertThat(line)
                .contains("channel=TOKEN_CLAIM", "boundTenant=" + TENANT_A));
        assertThat(output.getAll()).doesNotContain(TENANT_B.toString());
    }

    @Test
    void aTokenTenantClaimIsOnlyCrossCheckedNeverUsed(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/test/rows").with(signedIn(ALICE, b -> b.claim("tenant", TENANT_A.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].tenantId", everyItem(is(TENANT_A.toString()))));
        mvc.perform(get("/api/v1/me").with(signedIn(BOB, b -> b.claim("tenant", "tenant-b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_B.toString()));
        assertThat(denials(output)).isEmpty();
    }

    @Test
    void theTenantComesFromTheBootstrapForTheTokensIssuerAndSubject() throws Exception {
        mvc.perform(get("/api/v1/me").with(jwt().jwt(b -> b.claim("iss", "https://other-issuer.test")
                        .subject(ALICE).audience(List.of(AUDIENCE)))))
                .andExpect(status().isForbidden());
        assertThat(bootstrap.calls).containsExactly("https://other-issuer.test|" + ALICE);
    }

    // The chain (ADR-007 Decision 4; brief items 1-2)

    @Test
    void everythingOutsideTheApiAndHealthIsDenied() throws Exception {
        mvc.perform(get("/elsewhere").with(signedIn(ALICE))).andExpect(status().isForbidden());
        mvc.perform(get("/elsewhere")).andExpect(status().isUnauthorized());
    }

    @Test
    void theHealthProbeNeedsNoIdentity() throws Exception {
        int code = mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        assertThat(code).isNotIn(401, 403);
    }

    @Test
    void theApiIsStatelessAndNeedsNoCsrfTokenForBearerCalls() throws Exception {
        MvcResult result = mvc.perform(post("/api/test/rows/search").with(signedIn(ALICE))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(TENANT_B.toString()))))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }
}
