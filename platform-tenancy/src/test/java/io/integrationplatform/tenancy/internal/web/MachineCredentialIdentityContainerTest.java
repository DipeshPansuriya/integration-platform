package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CUSTOMER_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.SYSTEM_A;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.AUDIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.tenancy.TenancyTestApplication;
import io.integrationplatform.tenancy.internal.rls.RlsTestDatabase;
import io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures;
import io.integrationplatform.tenancy.internal.web.SourceCallsProbeController.Persisted;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * E4-F1-S2 "A source is identified by its own credential" (T-098) end to end inside platform-tenancy: real token
 * validation against the loopback issuer ({@link LocalIssuer} mints client-credentials-shaped tokens: {@code azp} = the
 * client id, {@code sub} = the service account, {@code aud} = the platform API), the real resolver chain, and
 * PostgreSQL with the E4 migrations as the restricted login. Keycloak-issued tokens re-prove this in lane A, wave 2.
 */
@Tag("container")
@SpringBootTest(classes = TenancyTestApplication.class, properties = {
    "spring.config.import=optional:classpath:config/tenancy-security.yml",
    "spring.flyway.enabled=false",
    "OIDC_AUDIENCE=" + AUDIENCE
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MachineCredentialIdentityContainerTest {

    static final LocalIssuer ISSUER = LocalIssuer.start();

    static final String CLIENT_ACTIVE = "it-source-a-" + UUID.randomUUID();
    static final String CLIENT_REVOKED = "it-source-a-revoked-" + UUID.randomUUID();
    static final String CLIENT_PENDING = "it-source-a-pending-" + UUID.randomUUID();
    static final String HUMAN = "it-alice-" + UUID.randomUUID();
    static final String SERVICE_ACCOUNT = "service-account-" + UUID.randomUUID();

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry registry) {
        registry.add("OIDC_ISSUER_URI", ISSUER::issuer);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RestrictedLogin {

        /** The application's restricted login (ipaas_app), as in production; Flyway already ran as the owner. */
        @Bean
        HikariDataSource dataSource() {
            return RlsTestDatabase.get().appPool(4);
        }
    }

    @BeforeAll
    static void credentials() throws SQLException {
        SourceCredentialFixtures.seed();
        String issuer = ISSUER.issuer();
        SourceCredentialFixtures.insertCredential(UUID.randomUUID(), issuer, TENANT_A, SYSTEM_A, CUSTOMER_A,
                CLIENT_ACTIVE, "ACTIVE");
        SourceCredentialFixtures.insertCredential(UUID.randomUUID(), issuer, TENANT_A, SYSTEM_A, CUSTOMER_A,
                CLIENT_REVOKED, "REVOKED");
        SourceCredentialFixtures.insertCredential(UUID.randomUUID(), issuer, TENANT_A, SYSTEM_A, CUSTOMER_A,
                CLIENT_PENDING, "PENDING");
        try (Connection owner = RlsTestDatabase.get().ownerConnection(); PreparedStatement user = owner.prepareStatement(
                "insert into public.app_user (tenant_id, issuer, subject, status) values (?::uuid, ?, ?, 'ACTIVE')")) {
            user.setString(1, TENANT_A.toString());
            user.setString(2, issuer);
            user.setString(3, HUMAN);
            user.executeUpdate();
        }
    }

    @AfterAll
    static void stop() {
        ISSUER.close();
    }

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void reset() {
        SourceCallsProbeController.PERSISTED.clear();
    }

    private String machineToken(String clientId) {
        return machineToken(clientId, b -> { });
    }

    private String machineToken(String clientId, Consumer<JWTClaimsSet.Builder> changes) {
        return ISSUER.rs256(ISSUER.claims(SERVICE_ACCOUNT, b -> {
            b.claim("azp", clientId);
            changes.accept(b);
        }));
    }

    private ResultActions call(String token, String json) throws Exception {
        return mvc.perform(post("/api/test/source-messages")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static List<String> denials(CapturedOutput output, String channel) {
        return output.getAll().lines()
                .filter(line -> line.contains("event=tenant.denied") && line.contains("channel=" + channel))
                .toList();
    }

    // Scenario: The customer comes from the credential

    @Test
    @DisplayName("S2: no customerId in the body - the customer is the credential's (A), the tenant is the source's")
    void withoutACustomerIdTheCustomerComesFromTheCredential() throws Exception {
        call(machineToken(CLIENT_ACTIVE), "{\"orderId\":\"o-1\",\"amount\":10}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_A))
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()))
                .andExpect(jsonPath("$.humanBound").value(false))
                .andExpect(jsonPath("$.visibleTenants", contains(TENANT_A.toString())));
        assertThat(SourceCallsProbeController.PERSISTED).containsExactly(new Persisted(CUSTOMER_A, TENANT_A));
    }

    @Test
    @DisplayName("S2: customerId A in the body (top level and in every bulk record) - the customer is A")
    void aMatchingCustomerIdIsAccepted() throws Exception {
        call(machineToken(CLIENT_ACTIVE), "{\"customerId\":\"CUSTOMER-A\",\"orderId\":\"o-2\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_A));
        call(machineToken(CLIENT_ACTIVE),
                "{\"records\":[{\"customerId\":\"CUSTOMER-A\"},{\"customer_id\":\" CUSTOMER-A \"},{\"customerId\":null}]}")
                .andExpect(status().isOk());
        assertThat(SourceCallsProbeController.PERSISTED).hasSize(2);
    }

    // Scenario: A body customerId mismatch is rejected

    @Test
    @DisplayName("S2 @negative @security: customerId B - 403 before any controller, nothing persisted, audited")
    void aMismatchedCustomerIdIsRefusedNothingPersistedAndAudited(CapturedOutput output) throws Exception {
        call(machineToken(CLIENT_ACTIVE), "{\"customerId\":\"CUSTOMER-B\",\"orderId\":\"o-3\"}")
                .andExpect(status().isForbidden());
        call(machineToken(CLIENT_ACTIVE),
                "{\"records\":[{\"customerId\":\"CUSTOMER-A\"},{\"customerId\":\"CUSTOMER-B\"}]}")
                .andExpect(status().isForbidden());
        call(machineToken(CLIENT_ACTIVE), "{\"customerId\":{\"id\":\"CUSTOMER-A\"}}")
                .andExpect(status().isForbidden());

        assertThat(SourceCallsProbeController.PERSISTED).as("nothing persisted").isEmpty();
        assertThat(denials(output, "BODY_CUSTOMER"))
                .hasSize(3)
                .allSatisfy(line -> assertThat(line).contains("boundTenant=" + TENANT_A)
                        .contains("path=/api/test/source-messages").doesNotContain("CUSTOMER-B"));
    }

    // Scenario: A revoked credential is refused

    @Test
    @DisplayName("S2 @negative @security: a revoked credential's still-valid token is refused with 401 and recorded")
    void aRevokedCredentialIsRefusedWith401(CapturedOutput output) throws Exception {
        call(machineToken(CLIENT_REVOKED), "{\"orderId\":\"o-4\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
        call(machineToken(CLIENT_PENDING), "{\"orderId\":\"o-5\"}").andExpect(status().isUnauthorized());
        call(machineToken("no-such-client"), "{\"orderId\":\"o-6\"}").andExpect(status().isUnauthorized());

        assertThat(SourceCallsProbeController.PERSISTED).isEmpty();
        assertThat(denials(output, "INACTIVE_CREDENTIAL")).hasSize(3);
    }

    @Test
    @DisplayName("S2 @negative @security: an expired machine token is refused with 401")
    void anExpiredMachineTokenIsRefusedWith401() throws Exception {
        Instant now = Instant.now();
        call(machineToken(CLIENT_ACTIVE, b -> b.issueTime(Date.from(now.minusSeconds(900)))
                        .expirationTime(Date.from(now.minusSeconds(600)))), "{\"orderId\":\"o-7\"}")
                .andExpect(status().isUnauthorized());
        assertThat(SourceCallsProbeController.PERSISTED).isEmpty();
    }

    // The machine principal binds a tenant but never a human

    @Test
    @DisplayName("S2 @security: a source is never a console user - the human-only endpoint refuses it with 403")
    void aSourceIsRefusedByHumanOnlyEndpoints() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + machineToken(CLIENT_ACTIVE)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S2 @negative @tenant: a forged tenant header on a source call changes nothing and is recorded")
    void aForgedTenantHeaderOnASourceCallIsRecorded(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/test/source-messages")
                        .header("Authorization", "Bearer " + machineToken(CLIENT_ACTIVE))
                        .header("X-Tenant-Id", "tenant-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()));
        assertThat(denials(output, "HEADER")).hasSize(1);
        assertThat(denials(output, "BODY_FIELD")).hasSize(1);
    }

    @Test
    @DisplayName("S2 @negative @tenant: a tenant claim naming another tenant in a machine token is 403 and recorded")
    void aForgedTenantClaimInAMachineTokenIsRefused(CapturedOutput output) throws Exception {
        call(machineToken(CLIENT_ACTIVE, b -> b.claim("tenant", "tenant-b")), "{}")
                .andExpect(status().isForbidden());
        assertThat(SourceCallsProbeController.PERSISTED).isEmpty();
        assertThat(denials(output, "TOKEN_CLAIM")).hasSize(1);
    }

    @Test
    @DisplayName("Humans first: a bound user's token (carrying an azp of its own) still binds the human, not a source")
    void aBoundHumanIsResolvedBeforeAnyClient() throws Exception {
        String token = ISSUER.rs256(ISSUER.claims(HUMAN, b -> b.claim("azp", CLIENT_ACTIVE)));
        call(token, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.humanBound").value(true))
                .andExpect(jsonPath("$.customerId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()));
    }

    @Test
    @DisplayName("Human behaviour unchanged: a valid token with no binding and no azp is still 403")
    void anUnboundHumanWithoutAzpIsStill403() throws Exception {
        call(ISSUER.rs256(ISSUER.claims("nobody-" + UUID.randomUUID(), b -> { })), "{}")
                .andExpect(status().isForbidden());
    }
}
