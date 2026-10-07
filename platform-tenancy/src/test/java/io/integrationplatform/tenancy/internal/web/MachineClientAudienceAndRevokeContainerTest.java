package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_A;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.USER_ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.identity.KeycloakRealmContainer;
import io.integrationplatform.tenancy.TenancyTestApplication;
import io.integrationplatform.tenancy.internal.credential.IdpDisableReconciliation;
import io.integrationplatform.tenancy.internal.credential.SourceCredentialRevocation;
import io.integrationplatform.tenancy.internal.principal.TenantBoundAuthentication;
import io.integrationplatform.tenancy.internal.rls.RlsTestDatabase;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin.CreatedClient;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E4-F1-S5 (T-186; ADR-007 Decisions 3 and 4, S-j) end to end in platform-tenancy: the REAL Keycloak realm (lane D's
 * {@link KeycloakRealmContainer}, read-only), PostgreSQL with every migration as the restricted login, the real token
 * validation and resolver chain. Clients are created through the platform's admin adapter - the console issue path
 * (pending row -> client -> active) is E5 T-103, so the credential rows come from this test's own SQL fixture.
 * Container group (R03), PostgreSQL + Keycloak, one Docker suite at a time (R06).
 */
@Tag("container")
@SpringBootTest(classes = TenancyTestApplication.class, properties = {
    "spring.config.import=optional:classpath:config/tenancy-security.yml",
    "spring.flyway.enabled=false",
    "OIDC_AUDIENCE=" + MachineClientAudienceAndRevokeContainerTest.AUDIENCE
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(MachineClientAudienceAndRevokeContainerTest.RestrictedLogin.class)
class MachineClientAudienceAndRevokeContainerTest {

    static final String AUDIENCE = "platform-api";
    private static final String CUSTOMER = "CUSTOMER-T186";
    private static final UUID SYSTEM = UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(AUDIENCE,
            "http://localhost:1/login/oauth2/code/keycloak", "http://localhost:1/");

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        KEYCLOAK.start();
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        // R07: the admin client's secret by its environment variable NAME; the value is random per run, memory only
        registry.add("PLATFORM_ADMIN_CLIENT_SECRET", KEYCLOAK::platformAdminClientSecret);
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
    static void sourceSystem() throws SQLException {
        try (Connection owner = RlsTestDatabase.get().ownerConnection(); PreparedStatement system =
                owner.prepareStatement("insert into public.system (id, tenant_id, code) values (?::uuid, ?::uuid, ?)")) {
            system.setString(1, SYSTEM.toString());
            system.setString(2, TENANT_A.toString());
            system.setString(3, "erp-t186-" + SYSTEM);
            system.executeUpdate();
        }
    }

    @AfterAll
    static void stop() {
        KEYCLOAK.stop();
    }

    @AfterEach
    void unbind() {
        SecurityContextHolder.clearContext();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IdentityProviderAdmin identityProviderAdmin;

    @Autowired
    private SourceCredentialStore credentials;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private IdpDisableReconciliation reconciliation;

    // Scenario: The default audience scope

    @Test
    @DisplayName("S5: a client created through the platform gets a token whose aud names the platform API; the API accepts it")
    void aPlatformCreatedClientCarriesTheAudience() throws Exception {
        CreatedClient client = identityProviderAdmin.createConfidentialClient("t186-src-" + UUID.randomUUID());
        credential(client.clientId(), "ACTIVE");
        assertThat(defaultScopes(client.clientId())).contains(KeycloakRealmContainer.AUDIENCE_SCOPE);

        String token = KEYCLOAK.clientCredentialsToken(client.clientId(), client.secret());

        assertThat(audiences(token)).contains(AUDIENCE);
        call(token).andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value(CUSTOMER));
    }

    // @negative @security Scenario: A client without the audience is refused

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("S5 @negative: a realm client WITHOUT the default scope gets a token the API refuses - 401, audited")
    void aClientWithoutTheAudienceIsRefusedAndAudited(CapturedOutput output) throws Exception {
        KeycloakRealmContainer.ClientCredentials client =
                KEYCLOAK.createConfidentialClient("t186-noaud-" + UUID.randomUUID(), false);
        credential(client.clientId(), "ACTIVE"); // even a registered, ACTIVE credential: the token itself is refused
        String token = KEYCLOAK.clientCredentialsToken(client.clientId(), client.secret());
        assertThat(audiences(token)).doesNotContain(AUDIENCE);

        call(token).andExpect(status().isUnauthorized());

        assertThat(output.getAll().lines().filter(line -> line.contains("event=token.refused")).toList())
                .singleElement().satisfies(line -> assertThat(line)
                        .contains("method=POST", "path=/api/test/source-messages")
                        .doesNotContain(token));
    }

    // @negative @security Scenario: Revoke with Keycloak down

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("S5 @negative: revoke with Keycloak's admin API down - the row bites at once, the client is disabled later")
    void revokeWithKeycloakDown() throws Exception {
        CreatedClient client = identityProviderAdmin.createConfidentialClient("t186-revoke-" + UUID.randomUUID());
        UUID credentialId = credential(client.clientId(), "ACTIVE");
        String token = KEYCLOAK.clientCredentialsToken(client.clientId(), client.secret());
        call(token).andExpect(status().isOk());

        // Given Keycloak's admin API is unavailable (the adapter points at a closed port)
        SourceCredentialRevocation revocationWhileDown = new SourceCredentialRevocation(credentials,
                new TransactionTemplate(transactions), adapterAt(closedPortIssuer()));
        // When a tenant admin revokes the credential
        bindTenantAdminOfTenantA();
        SourceCredentialRevocation.Outcome outcome = revocationWhileDown.revoke(credentialId);
        SecurityContextHolder.clearContext();

        // Then the platform row is revoked first ...
        assertThat(outcome).isEqualTo(SourceCredentialRevocation.Outcome.IDP_DISABLE_PENDING);
        assertThat(row(credentialId)).isEqualTo("REVOKED|true");
        // ... and API calls with that credential are refused at once - its token has not expired at Keycloak
        call(token).andExpect(status().isUnauthorized());
        assertThat(clientEnabled(client.clientId())).as("Keycloak still has the client enabled").isTrue();
        assertThat(clientCredentialsStatus(client)).as("Keycloak still issues tokens").isEqualTo(200);

        // And the Keycloak client is disabled by the credential reconciliation after Keycloak returns
        bindTenantAdminOfTenantA();
        IdpDisableReconciliation.Result result = reconciliation.reconcile();
        SecurityContextHolder.clearContext();

        assertThat(result.cleared()).isEqualTo(1);
        assertThat(row(credentialId)).isEqualTo("REVOKED|false");
        assertThat(clientEnabled(client.clientId())).as("disabled at Keycloak").isFalse();
        assertThat(clientCredentialsStatus(client)).as("no new token for a disabled client").isNotEqualTo(200);
        call(token).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S5: revoke with Keycloak up - row first, then the client is disabled at once and nothing stays pending")
    void revokeWithKeycloakUp() throws Exception {
        CreatedClient client = identityProviderAdmin.createConfidentialClient("t186-revoke-up-" + UUID.randomUUID());
        UUID credentialId = credential(client.clientId(), "ACTIVE");

        bindTenantAdminOfTenantA();
        SourceCredentialRevocation.Outcome outcome = new SourceCredentialRevocation(credentials,
                new TransactionTemplate(transactions), identityProviderAdmin).revoke(credentialId);
        SecurityContextHolder.clearContext();

        assertThat(outcome).isEqualTo(SourceCredentialRevocation.Outcome.REVOKED);
        assertThat(row(credentialId)).isEqualTo("REVOKED|false");
        assertThat(clientEnabled(client.clientId())).isFalse();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private ResultActions call(String token) throws Exception {
        return mvc.perform(post("/api/test/source-messages")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"t186\"}"));
    }

    /** This test's own SQL fixture: one credential row of tenant A for a client of the Keycloak issuer. */
    private static UUID credential(String clientId, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = RlsTestDatabase.get().ownerConnection(); PreparedStatement insert =
                owner.prepareStatement("insert into public.source_credential (id, tenant_id, source_system_id,"
                        + " customer_id, issuer, client_id, status) values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?)")) {
            insert.setString(1, id.toString());
            insert.setString(2, TENANT_A.toString());
            insert.setString(3, SYSTEM.toString());
            insert.setString(4, CUSTOMER);
            insert.setString(5, KEYCLOAK.issuerUri());
            insert.setString(6, clientId);
            insert.setString(7, status);
            insert.executeUpdate();
        }
        return id;
    }

    private static String row(UUID credentialId) throws SQLException {
        try (Connection owner = RlsTestDatabase.get().ownerConnection(); PreparedStatement select =
                owner.prepareStatement("select status || '|' || idp_disable_pending from public.source_credential"
                        + " where id = ?::uuid")) {
            select.setString(1, credentialId.toString());
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    /** The tenant-admin request's binding (the revoke endpoint is E5's): alice of tenant A. */
    private static void bindTenantAdminOfTenantA() {
        Jwt jwt = Jwt.withTokenValue("bound").header("alg", "none").subject("alice").issuer("https://idp.test")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        LoginBinding alice = new LoginBinding(USER_ALICE, "ACTIVE", TENANT_A, "tenant-a", "ACTIVE",
                ZoneId.of("Asia/Kolkata"));
        SecurityContextHolder.getContext().setAuthentication(
                new TenantBoundAuthentication(new JwtAuthenticationToken(jwt), alice));
    }

    private static IdentityProviderAdmin adapterAt(String issuer) {
        Supplier<String> secret = KEYCLOAK::platformAdminClientSecret;
        return new KeycloakAdminAdapter(issuer, KeycloakRealmContainer.ADMIN_CLIENT, secret,
                KeycloakAdminAdapter.restTemplate(IdentityProviderSettings.defaults(), new SimpleMeterRegistry(),
                        ObservationRegistry.NOOP), ObservationRegistry.NOOP);
    }

    private static String closedPortIssuer() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        return "http://127.0.0.1:" + port + "/realms/" + KeycloakRealmContainer.REALM;
    }

    private static List<String> audiences(String token) {
        JsonNode claims = JSON.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                StandardCharsets.UTF_8));
        List<String> audiences = new ArrayList<>();
        JsonNode aud = claims.path("aud");
        if (aud.isArray()) {
            aud.forEach(value -> audiences.add(value.asString()));
        } else if (!aud.isMissingNode()) {
            audiences.add(aud.asString());
        }
        return audiences;
    }

    private static JsonNode client(String clientId) {
        HttpResponse<String> found = KEYCLOAK.admin("GET", "/clients?clientId=" + clientId, null);
        assertThat(found.statusCode()).isEqualTo(200);
        JsonNode clients = JSON.readTree(found.body());
        assertThat(clients.size()).isEqualTo(1);
        return clients.get(0);
    }

    private static boolean clientEnabled(String clientId) {
        return client(clientId).get("enabled").asBoolean();
    }

    private static List<String> defaultScopes(String clientId) {
        HttpResponse<String> scopes = KEYCLOAK.admin("GET",
                "/clients/" + client(clientId).get("id").asString() + "/default-client-scopes", null);
        assertThat(scopes.statusCode()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        JSON.readTree(scopes.body()).forEach(scope -> names.add(scope.get("name").asString()));
        return names;
    }

    private static int clientCredentialsStatus(CreatedClient client) {
        return KEYCLOAK.tokenEndpoint(Map.of("grant_type", "client_credentials", "client_id", client.clientId(),
                "client_secret", client.secret())).statusCode();
    }
}
