package io.integrationplatform.identity;

import io.integrationplatform.tenancy.TenancyTestImages;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E4 (T-097) test fixture: Keycloak (the pinned {@link TenancyTestImages#KEYCLOAK}) with realm {@code platform} imported
 * from the real {@code compose/keycloak/platform-realm.json} - the same file compose mounts, so the tests prove the
 * realm the deployment uses. Plain Testcontainers {@link GenericContainer}: {@code start-dev --import-realm}, ready
 * when the management health endpoint says so.
 *
 * <p>R07: every secret (the master-realm admin, the console and admin client secrets, every user password) is
 * generated at random per run, passed through container environment variables into the realm file's placeholders,
 * and held in memory only - never printed (no {@code toString} carries one).
 *
 * <p>Reused read-only by later E4 lanes (C, B, A): keep this API small and stable.
 */
public final class KeycloakRealmContainer extends GenericContainer<KeycloakRealmContainer> {

    public static final String REALM = "platform";
    public static final String CONSOLE_CLIENT = "platform-console";
    public static final String ADMIN_CLIENT = "platform-admin";
    public static final String AUDIENCE_SCOPE = "platform-api-audience";

    private static final int HTTP = 8080;
    private static final int MANAGEMENT = 9000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String audience;
    private final String adminUser = "fixture-admin-" + randomToken(6);
    private final String adminPassword = randomToken(24);
    private final String consoleSecret = randomToken(32);
    private final String platformAdminSecret = randomToken(32);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** A realm user: its token subject ({@code sub}, Keycloak's user id) and its random password. */
    public record RealmUser(String username, String subject, String password) {

        @Override
        public String toString() {
            return "RealmUser[" + username + ", sub=" + subject + "]";
        }
    }

    /** A confidential client created at runtime; the secret exists in memory only. */
    public record ClientCredentials(String clientId, String secret) {

        @Override
        public String toString() {
            return "ClientCredentials[" + clientId + "]";
        }
    }

    /**
     * @param audience             the platform API audience (the backend's {@code OIDC_AUDIENCE})
     * @param consoleRedirectUri   the backend's {@code .../login/oauth2/code/keycloak} the console client accepts
     * @param postLogoutRedirectUri where Keycloak may send the browser after logout
     */
    public KeycloakRealmContainer(String audience, String consoleRedirectUri, String postLogoutRedirectUri) {
        super(DockerImageName.parse(TenancyTestImages.KEYCLOAK));
        this.audience = Objects.requireNonNull(audience, "audience");
        withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", adminUser);
        withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", adminPassword);
        withEnv("KC_HEALTH_ENABLED", "true");
        withEnv("JAVA_OPTS_KC_HEAP", "-Xms64m -Xmx512m"); // R06: one heavy JVM in a container, capped
        withEnv("TZ", "Etc/UTC");
        withEnv("OIDC_AUDIENCE", audience);
        withEnv("CONSOLE_OIDC_CLIENT_SECRET", consoleSecret);
        withEnv("CONSOLE_OIDC_REDIRECT_URI", consoleRedirectUri);
        withEnv("CONSOLE_POST_LOGOUT_REDIRECT_URI", postLogoutRedirectUri);
        withEnv("PLATFORM_ADMIN_CLIENT_SECRET", platformAdminSecret);
        withEnv("OPERATOR_AGENT_CLIENT_SECRET", randomToken(32)); // T162: never the literal placeholder
        withCopyFileToContainer(MountableFile.forHostPath(realmFile()), "/opt/keycloak/data/import/platform-realm.json");
        withCommand("start-dev", "--import-realm");
        withExposedPorts(HTTP, MANAGEMENT);
        waitingFor(Wait.forHttp("/health/ready").forPort(MANAGEMENT).withStartupTimeout(Duration.ofMinutes(4)));
    }

    /** The realm file compose mounts: {@code <repo>/compose/keycloak/platform-realm.json}. */
    public static Path realmFile() {
        Path module = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        Path file = module.getParent().resolve("compose/keycloak/platform-realm.json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("realm file not found at " + file);
        }
        return file;
    }

    /** {@code http://<host>:<port>/realms/platform} - the backend's {@code OIDC_ISSUER_URI}. */
    public String issuerUri() {
        return baseUri() + "/realms/" + REALM;
    }

    public String audience() {
        return audience;
    }

    /** The console client's secret for the backend's {@code CONSOLE_OIDC_CLIENT_SECRET}; memory only. */
    public String consoleClientSecret() {
        return consoleSecret;
    }

    /** The platform-admin service account's secret ({@code PLATFORM_ADMIN_CLIENT_SECRET}); memory only. */
    public String platformAdminClientSecret() {
        return platformAdminSecret;
    }

    /**
     * Creates an enabled user with a random password and a complete profile (no required action stops the login).
     * Each extra claim becomes a user attribute and a test-only attribute mapper on the console client, so the
     * console's access token carries it (for example {@code tenant=<another tenant>} for the forged-claim check).
     */
    public RealmUser createUser(String username, Map<String, String> extraClaims) {
        if (!extraClaims.isEmpty()) {
            allowUnmanagedAttributes();
            for (String claim : extraClaims.keySet()) {
                addConsoleClaimMapper(claim);
            }
        }
        String password = randomToken(20);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("enabled", true);
        user.put("emailVerified", true);
        user.put("firstName", "Test");
        user.put("lastName", username);
        user.put("email", username + "@example.test");
        user.put("credentials", List.of(Map.of("type", "password", "value", password, "temporary", false)));
        user.put("attributes", extraClaims.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue()))));
        HttpResponse<String> created = admin("POST", "/users", user);
        expect(created, 201, "create user " + username);
        String location = created.headers().firstValue("Location").orElseThrow();
        return new RealmUser(username, location.substring(location.lastIndexOf('/') + 1), password);
    }

    public RealmUser createUser(String username) {
        return createUser(username, Map.of());
    }

    /** Disables a user in Keycloak (its sessions can no longer log in). */
    public void disableUser(RealmUser user) {
        expect(admin("PUT", "/users/" + user.subject(), Map.of("enabled", false)), 204, "disable " + user.username());
    }

    /**
     * Creates a confidential client-credentials client. With {@code withAudienceScope} it keeps the realm default
     * {@value #AUDIENCE_SCOPE} scope (ADR-007 Decision 3, S-j); without it the scope is removed, so its tokens lack the
     * platform audience. The secret is returned in memory only.
     */
    public ClientCredentials createConfidentialClient(String clientId, boolean withAudienceScope) {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("clientId", clientId);
        client.put("enabled", true);
        client.put("protocol", "openid-connect");
        client.put("publicClient", false);
        client.put("clientAuthenticatorType", "client-secret");
        client.put("serviceAccountsEnabled", true);
        client.put("standardFlowEnabled", false);
        client.put("directAccessGrantsEnabled", false);
        client.put("implicitFlowEnabled", false);
        expect(admin("POST", "/clients", client), 201, "create client " + clientId);
        String id = clientUuid(clientId);
        if (!withAudienceScope) {
            String scopeId = clientScopeId(AUDIENCE_SCOPE);
            expect(admin("DELETE", "/clients/" + id + "/default-client-scopes/" + scopeId, null), 204,
                    "remove the audience scope from " + clientId);
        }
        HttpResponse<String> secret = admin("GET", "/clients/" + id + "/client-secret", null);
        expect(secret, 200, "read the secret of " + clientId);
        return new ClientCredentials(clientId, read(secret.body()).get("value").asString());
    }

    /** The access token of a client-credentials grant at the realm's token endpoint. */
    public String clientCredentialsToken(String clientId, String secret) {
        HttpResponse<String> response = tokenRequest(issuerUri() + "/protocol/openid-connect/token",
                Map.of("grant_type", "client_credentials", "client_id", clientId, "client_secret", secret));
        expect(response, 200, "client credentials token for " + clientId);
        return read(response.body()).get("access_token").asString();
    }

    /** One form post to the realm's token endpoint, answered as it comes (for refusal checks). */
    public HttpResponse<String> tokenEndpoint(Map<String, String> form) {
        return tokenRequest(issuerUri() + "/protocol/openid-connect/token", form);
    }

    /** The realm as Keycloak serves it to an administrator (for checks of the imported settings). */
    public JsonNode realmRepresentation() {
        HttpResponse<String> response = admin("GET", "", null);
        expect(response, 200, "read realm");
        return read(response.body());
    }

    /** One administrative call on the platform realm as the fixture's master-realm administrator. */
    public HttpResponse<String> admin(String method, String realmPath, Object body) {
        return send(method, baseUri() + "/admin/realms/" + REALM + realmPath, masterAdminToken(), body);
    }

    /** One call to Keycloak with any bearer token (for example the platform-admin service account's). */
    public HttpResponse<String> send(String method, String url, String bearer, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + bearer);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        return call(request.build());
    }

    /**
     * Drives the console login the way a browser does, for tests without a browser (the Playwright twin lives in
     * {@code ui/tests/screens/keycloak-login.mjs}): the backend's {@code /oauth2/authorization/keycloak} redirect,
     * the realm's login form, the code callback - every response is kept for inspection.
     */
    public ConsoleBrowser browser(URI backend) {
        return new ConsoleBrowser(backend, http);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private String baseUri() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP);
    }

    private String masterAdminToken() {
        HttpResponse<String> response = tokenRequest(baseUri() + "/realms/master/protocol/openid-connect/token",
                Map.of("grant_type", "password", "client_id", "admin-cli", "username", adminUser,
                        "password", adminPassword));
        expect(response, 200, "master admin token");
        return read(response.body()).get("access_token").asString();
    }

    private HttpResponse<String> tokenRequest(String url, Map<String, String> form) {
        return call(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(form)))
                .build());
    }

    private void allowUnmanagedAttributes() {
        HttpResponse<String> profile = admin("GET", "/users/profile", null);
        expect(profile, 200, "read the user profile");
        Map<String, Object> config = JSON.readValue(profile.body(), Map.class);
        if (!"ENABLED".equals(config.get("unmanagedAttributePolicy"))) {
            config.put("unmanagedAttributePolicy", "ENABLED");
            expect(admin("PUT", "/users/profile", config), 200, "allow unmanaged attributes");
        }
    }

    private void addConsoleClaimMapper(String claim) {
        Map<String, Object> mapper = Map.of(
                "name", "test-claim-" + claim,
                "protocol", "openid-connect",
                "protocolMapper", "oidc-usermodel-attribute-mapper",
                "config", Map.of(
                        "user.attribute", claim,
                        "claim.name", claim,
                        "jsonType.label", "String",
                        "access.token.claim", "true",
                        "id.token.claim", "false",
                        "introspection.token.claim", "true"));
        HttpResponse<String> response =
                admin("POST", "/clients/" + clientUuid(CONSOLE_CLIENT) + "/protocol-mappers/models", mapper);
        if (response.statusCode() != 201 && response.statusCode() != 409) {
            expect(response, 201, "add the test claim mapper " + claim);
        }
    }

    private String clientUuid(String clientId) {
        HttpResponse<String> response =
                admin("GET", "/clients?clientId=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8), null);
        expect(response, 200, "find client " + clientId);
        JsonNode clients = read(response.body());
        if (clients.size() != 1) {
            throw new IllegalStateException("expected one client " + clientId + ", found " + clients.size());
        }
        return clients.get(0).get("id").asString();
    }

    private String clientScopeId(String name) {
        HttpResponse<String> response = admin("GET", "/client-scopes", null);
        expect(response, 200, "list client scopes");
        for (JsonNode scope : read(response.body())) {
            if (name.equals(scope.get("name").asString())) {
                return scope.get("id").asString();
            }
        }
        throw new IllegalStateException("client scope " + name + " not found");
    }

    private HttpResponse<String> call(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + request.uri().getPath() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    static String formBody(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    static JsonNode read(String body) {
        return JSON.readTree(body);
    }

    private static void expect(HttpResponse<String> response, int status, String what) {
        if (response.statusCode() != status) {
            // The body of an admin error never carries a secret (Keycloak answers with an error code and message).
            throw new IllegalStateException(what + ": expected " + status + " but was " + response.statusCode()
                    + " " + response.body());
        }
    }

    static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
