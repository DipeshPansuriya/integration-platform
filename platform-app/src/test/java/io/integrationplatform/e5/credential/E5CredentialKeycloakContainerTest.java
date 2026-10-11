package io.integrationplatform.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.identity.ConsoleBrowser;
import io.integrationplatform.identity.ConsoleBrowser.Exchange;
import io.integrationplatform.identity.KeycloakRealmContainer;
import io.integrationplatform.identity.KeycloakRealmContainer.RealmUser;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E5-F1-S3 (T-103; ADR-007 Decisions 3 and 4) end to end: the whole application, PostgreSQL as the restricted login,
 * lane D's pinned Keycloak realm, the REAL Keycloak admin adapter (the platform-admin service account), console users
 * signed in through the realm's login form and source systems with Keycloak client-credentials tokens.
 *
 * <pre>
 * Scenario: Issue a source credential
 *   Given a tenant admin and a registered source system
 *   When they issue a credential
 *   Then a confidential Keycloak client is created, the secret is shown exactly once, and the action is audited
 *
 * &#64;negative &#64;security
 * Scenario: A revoked credential stops working at once
 *   Given a revoked source credential
 *   When the source calls with a token issued before the revoke
 *   Then the call is refused within the token lifetime (5 minutes) and every new token request is refused
 *
 * &#64;negative &#64;security
 * Scenario Outline: Only a tenant admin manages credentials
 *   Given a user holding only &lt;role&gt;
 *   When they issue, rotate or revoke a credential
 *   Then the server answers 403
 *   Examples: configurator | support | read-only        (and every target-credential write: MANAGE_TARGET_CREDENTIAL)
 *
 * &#64;negative &#64;security
 * Scenario: Secrets are write-only and never logged       (the whole action's log is searched for every secret)
 * </pre>
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@Import(E5SourceProbe.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("E5-F1-S3 credentials with the real Keycloak realm")
class E5CredentialKeycloakContainerTest {

    private static final int APP_PORT = freePort();
    private static final URI BACKEND = URI.create("http://localhost:" + APP_PORT);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final byte[] K1 = randomKey();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Container
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            BACKEND + "/login/oauth2/code/keycloak", BACKEND + "/");

    private static RestrictedLogin login;
    private static UUID tenantId;
    private static UUID sourceSystem;
    private static UUID targetEndpoint;
    private static final Map<Role, ConsoleBrowser> SESSIONS = new EnumMap<>(Role.class);

    enum Role { TENANT_ADMIN, CONFIGURATOR, SUPPORT, READ_ONLY }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KEYCLOAK.start();
        registry.add("server.port", () -> APP_PORT);
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        registry.add("CONSOLE_OIDC_CLIENT_ID", () -> KeycloakRealmContainer.CONSOLE_CLIENT);
        registry.add("CONSOLE_OIDC_CLIENT_SECRET", KEYCLOAK::consoleClientSecret);
        registry.add("CONSOLE_OIDC_REDIRECT_URI", () -> BACKEND + "/login/oauth2/code/keycloak");
        registry.add("CONSOLE_POST_LOGOUT_REDIRECT_URI", () -> BACKEND + "/");
        registry.add("CONSOLE_URL", () -> "/");
        // the real admin adapter: the platform-admin service account of lane D's realm (names only, R07)
        registry.add("PLATFORM_ADMIN_CLIENT_SECRET", KEYCLOAK::platformAdminClientSecret);
        registry.add("PLATFORM_CREDENTIAL_KEK_K1", () -> Base64.getEncoder().encodeToString(K1));
        registry.add("PLATFORM_CREDENTIAL_KEK_ACTIVE", () -> "K1");
    }

    @BeforeEach
    void seedOnce() throws Exception {
        if (tenantId != null) {
            return;
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        tenantId = UUID.randomUUID();
        sourceSystem = UUID.randomUUID();
        UUID targetSystem = UUID.randomUUID();
        targetEndpoint = UUID.randomUUID();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            execute(owner, "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')",
                    tenantId.toString(), "tnt-e5cred-" + tenantId.toString().substring(0, 8));
            execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction) VALUES (?::uuid,"
                    + " ?::uuid, ?, 'ERP', 'SOURCE')", sourceSystem.toString(), tenantId.toString(), "erp-e5");
            execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction, type, settings) VALUES"
                    + " (?::uuid, ?::uuid, ?, 'Partner', 'TARGET', 'API', ?::jsonb)", targetSystem.toString(),
                    tenantId.toString(), "partner-e5", "{\"baseUrl\":\"https://partner.example/\"}");
            execute(owner, "INSERT INTO public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                    + " http_method, auth_method, timeout_ms) VALUES (?::uuid, ?::uuid, ?::uuid, 'partner-e5-ep',"
                    + " 'Partner', 'https://partner.example/', 'POST', 'BASIC', 5000)", targetEndpoint.toString(),
                    tenantId.toString(), targetSystem.toString());
            for (Role role : Role.values()) {
                RealmUser user = KEYCLOAK.createUser("e5-" + role.name().toLowerCase().replace('_', '-') + "-"
                        + UUID.randomUUID().toString().substring(0, 6));
                UUID userId = UUID.randomUUID();
                execute(owner, "INSERT INTO app_user (id, tenant_id, issuer, subject, status) VALUES (?::uuid,"
                        + " ?::uuid, ?, ?, 'ACTIVE')", userId.toString(), tenantId.toString(), KEYCLOAK.issuerUri(),
                        user.subject());
                execute(owner, "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, ?)",
                        tenantId.toString(), userId.toString(), role.name());
                ConsoleBrowser browser = KEYCLOAK.browser(BACKEND);
                Exchange last = browser.login(user);
                assertThat(last.status()).as("console login of " + role).isEqualTo(302);
                browser.get("/api/v1/me");
                SESSIONS.put(role, browser);
            }
        }
    }

    private static Exchange as(Role role, String method, String path, String body) {
        return SESSIONS.get(role).api(method, path, body, true);
    }

    private static String issuePath() {
        return "/api/v1/admin/systems/" + sourceSystem + "/source-credentials";
    }

    private static String credentialPath(String id, String action) {
        return "/api/v1/admin/source-credentials/" + id + "/" + action;
    }

    private static JsonNode json(Exchange exchange) {
        return JSON.readTree(exchange.body());
    }

    private static HttpResponse<String> probe(String bearer) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(BACKEND.resolve(E5SourceProbe.PATH))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + bearer).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("Scenario: Issue a source credential - a Keycloak client the API accepts; the secret once; audited")
    void issueASourceCredential(CapturedOutput output) throws Exception {
        Exchange issued = as(Role.TENANT_ADMIN, "POST", issuePath(), "{\"customerId\":\"CUSTOMER-E5\"}");

        assertThat(issued.status()).as(issued.body()).isEqualTo(201);
        String id = json(issued).path("id").asString();
        String clientId = json(issued).path("clientId").asString();
        String secret = json(issued).path("secret").asString();

        HttpResponse<String> found = KEYCLOAK.admin("GET", "/clients?clientId=" + clientId, null);
        JsonNode client = JSON.readTree(found.body()).get(0);
        assertThat(client.path("publicClient").asBoolean()).as("confidential").isFalse();
        assertThat(client.path("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(client.path("defaultClientScopes").toString()).contains(KeycloakRealmContainer.AUDIENCE_SCOPE);

        String token = KEYCLOAK.clientCredentialsToken(clientId, secret);
        HttpResponse<String> accepted = probe(token);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(accepted.body()).contains(tenantId.toString());

        Exchange listed = as(Role.TENANT_ADMIN, "GET", issuePath(), null);
        assertThat(listed.body()).contains(id).doesNotContain(secret);
        assertThat(output).contains("event=credential.source_issued").doesNotContain(secret);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: A revoked credential stops working at once - the old token 401 now, new tokens refused")
    void aRevokedCredentialStopsWorkingAtOnce() throws Exception {
        Exchange issued = as(Role.TENANT_ADMIN, "POST", issuePath(), "{\"customerId\":\"CUSTOMER-E5\"}");
        String id = json(issued).path("id").asString();
        String clientId = json(issued).path("clientId").asString();
        String secret = json(issued).path("secret").asString();
        String issuedBeforeRevoke = KEYCLOAK.clientCredentialsToken(clientId, secret);
        assertThat(probe(issuedBeforeRevoke).statusCode()).isEqualTo(200);

        Exchange revoked = as(Role.TENANT_ADMIN, "POST", credentialPath(id, "revoke"), null);

        assertThat(revoked.status()).as(revoked.body()).isEqualTo(200);
        assertThat(json(revoked).path("idpDisablePending").asBoolean()).as("Keycloak confirmed the disable").isFalse();
        assertThat(probe(issuedBeforeRevoke).statusCode())
                .as("the still-unexpired token is refused at once, well within its 5-minute lifetime").isEqualTo(401);
        HttpResponse<String> newToken = KEYCLOAK.tokenEndpoint(Map.of("grant_type", "client_credentials",
                "client_id", clientId, "client_secret", secret));
        assertThat(newToken.statusCode()).as("every new token request is refused: " + newToken.body())
                .isBetween(400, 401);
    }

    @Test
    @DisplayName("Rotate: the new credential works and the old one keeps working during the grace window")
    void rotateKeepsBothValid() throws Exception {
        Exchange first = as(Role.TENANT_ADMIN, "POST", issuePath(), "{\"customerId\":\"CUSTOMER-E5\"}");
        Exchange rotated = as(Role.TENANT_ADMIN, "POST", credentialPath(json(first).path("id").asString(), "rotate"),
                null);

        assertThat(rotated.status()).as(rotated.body()).isEqualTo(201);
        assertThat(probe(KEYCLOAK.clientCredentialsToken(json(first).path("clientId").asString(),
                json(first).path("secret").asString())).statusCode()).isEqualTo(200);
        assertThat(probe(KEYCLOAK.clientCredentialsToken(json(rotated).path("clientId").asString(),
                json(rotated).path("secret").asString())).statusCode()).isEqualTo(200);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario Outline: configurator, support and read-only get 403 on issue, rotate, revoke and target writes")
    void onlyATenantAdminManagesCredentials() {
        Exchange issued = as(Role.TENANT_ADMIN, "POST", issuePath(), "{\"customerId\":\"CUSTOMER-E5\"}");
        String id = json(issued).path("id").asString();
        for (Role role : List.of(Role.CONFIGURATOR, Role.SUPPORT, Role.READ_ONLY)) {
            assertThat(as(role, "POST", issuePath(), "{\"customerId\":\"C\"}").status()).as(role + " issue")
                    .isEqualTo(403);
            assertThat(as(role, "POST", credentialPath(id, "rotate"), null).status()).as(role + " rotate")
                    .isEqualTo(403);
            assertThat(as(role, "POST", credentialPath(id, "revoke"), null).status()).as(role + " revoke")
                    .isEqualTo(403);
            assertThat(as(role, "PUT", "/api/v1/admin/target-endpoints/" + targetEndpoint + "/credentials",
                    "{\"fields\":{\"basicPassword\":\"refused\"}}").status()).as(role + " target write")
                    .isEqualTo(403);
        }
        assertThat(as(Role.TENANT_ADMIN, "GET", issuePath(), null).body()).contains("\"status\":\"ACTIVE\"");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: Secrets are write-only and never logged - the target secret reads masked, no log line holds it")
    void secretsAreWriteOnlyAndNeverLogged(CapturedOutput output) {
        String password = "e5-target-" + UUID.randomUUID();
        String path = "/api/v1/admin/target-endpoints/" + targetEndpoint + "/credentials";

        Exchange saved = as(Role.TENANT_ADMIN, "PUT", path, "{\"fields\":{\"basicPassword\":\"" + password + "\"}}");
        Exchange read = as(Role.TENANT_ADMIN, "GET", path, null);

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(saved.body()).doesNotContain(password).contains("********");
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body()).doesNotContain(password).contains("********");
        assertThat(output.getAll()).doesNotContain(password);
    }

    private static void execute(Connection owner, String sql, String... values) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port", e);
        }
    }
}
