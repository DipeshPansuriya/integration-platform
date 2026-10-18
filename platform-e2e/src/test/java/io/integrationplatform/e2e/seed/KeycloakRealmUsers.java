package io.integrationplatform.e2e.seed;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import io.integrationplatform.e2e.seed.TenantSeedPlan.SeedUser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minidev.json.JSONValue;

/**
 * Row 327 T157: realm users of the {@code platform} realm, created through Keycloak's REAL admin REST API (the way
 * platform-app's KeycloakRealmContainer does) - never through the compose realm import. The master admin login is the
 * run's generated bootstrap admin; every password stays in memory.
 *
 * <p>ponytail: a test-side fixture against a local container - one bounded timeout per call, no retry or breaker
 * (a failure fails the run, which is the wanted signal).
 */
public final class KeycloakRealmUsers {

    public static final String REALM = "platform";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUri;
    private final String adminUser;
    private final String adminPassword;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** {@code baseUri}: {@code http://<host>:<port>} of Keycloak. */
    public KeycloakRealmUsers(String baseUri, String adminUser, String adminPassword) {
        this.baseUri = baseUri;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
    }

    /** The realm's issuer as its discovery document states it - the {@code iss} its tokens carry. */
    public String issuer() {
        HttpResponse<String> discovery = send(HttpRequest.newBuilder(
                URI.create(baseUri + "/realms/" + REALM + "/.well-known/openid-configuration")).GET());
        expect(discovery, 200, "realm discovery");
        return JsonPath.read(discovery.body(), "$.issuer");
    }

    /** Creates {@code user} (enabled, a non-temporary password) and returns its subject - Keycloak's user id. */
    public String create(SeedUser user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", user.username());
        body.put("enabled", true);
        body.put("emailVerified", true);
        body.put("email", user.email());
        body.put("firstName", user.firstName());
        body.put("lastName", user.lastName());
        body.put("credentials", List.of(Map.of("type", "password", "value", user.password(), "temporary", false)));
        HttpResponse<String> created = send(HttpRequest.newBuilder(URI.create(baseUri + "/admin/realms/" + REALM
                        + "/users"))
                .header("Authorization", "Bearer " + masterAdminToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONValue.toJSONString(body))));
        expect(created, 201, "creating realm user " + user.username());
        String location = created.headers().firstValue("Location")
                .orElseThrow(() -> new IllegalStateException("Keycloak answered 201 without a Location"));
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /** {@code user}'s access token from the realm's {@code admin-cli} client (direct grant) - a real login. */
    public String accessToken(SeedUser user) {
        giveAdminCliTheBasicScope();
        HttpResponse<String> token = form(baseUri + "/realms/" + REALM + "/protocol/openid-connect/token",
                Map.of("grant_type", "password", "client_id", "admin-cli", "username", user.username(),
                        "password", user.password()));
        expect(token, 200, "token for " + user.username());
        return JsonPath.read(token.body(), "$.access_token");
    }

    /**
     * Keycloak 26 puts {@code sub} in an access token only through the {@code basic} client scope (F3). The
     * built-in {@code admin-cli} is created before the import adds the realm's client scopes, so it lacks it
     * (asking for {@code scope=basic} answers 400), and it issues lightweight access tokens, which drop
     * {@code sub} anyway. Test-only set-up of the e2e realm's admin-cli; the product clients are untouched.
     */
    private void giveAdminCliTheBasicScope() {
        String admin = "Bearer " + masterAdminToken();
        String realmAdmin = baseUri + "/admin/realms/" + REALM;
        HttpResponse<String> clients = send(HttpRequest.newBuilder(URI.create(realmAdmin + "/clients?clientId=admin-cli"))
                .header("Authorization", admin).GET());
        expect(clients, 200, "finding the realm's admin-cli");
        HttpResponse<String> scopes = send(HttpRequest.newBuilder(URI.create(realmAdmin + "/client-scopes"))
                .header("Authorization", admin).GET());
        expect(scopes, 200, "listing the realm's client scopes");
        List<String> basic = JsonPath.read(scopes.body(), "$[?(@.name == 'basic')].id");
        if (basic.isEmpty()) {
            throw new IllegalStateException("the realm has no basic client scope - sub cannot reach a token");
        }
        String clientId = JsonPath.read(clients.body(), "$[0].id");
        DocumentContext client = JsonPath.parse(clients.body());
        client.put("$[0].attributes", "client.use.lightweight.access.token.enabled", "false");
        HttpResponse<String> updated = send(HttpRequest.newBuilder(URI.create(realmAdmin + "/clients/" + clientId))
                .header("Authorization", admin).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSONValue.toJSONString(client.read("$[0]")))));
        expect(updated, 204, "turning off admin-cli's lightweight access token");
        HttpResponse<String> attached = send(HttpRequest.newBuilder(URI.create(realmAdmin + "/clients/" + clientId
                        + "/default-client-scopes/" + basic.get(0)))
                .header("Authorization", admin).PUT(HttpRequest.BodyPublishers.noBody()));
        expect(attached, 204, "giving admin-cli the basic client scope");
    }

    private String masterAdminToken() {
        HttpResponse<String> token = form(baseUri + "/realms/master/protocol/openid-connect/token",
                Map.of("grant_type", "password", "client_id", "admin-cli", "username", adminUser,
                        "password", adminPassword));
        expect(token, 200, "master admin token");
        return JsonPath.read(token.body(), "$.access_token");
    }

    private HttpResponse<String> form(String url, Map<String, String> fields) {
        String body = fields.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new IllegalStateException("Keycloak call failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling Keycloak", interrupted);
        }
    }

    private static void expect(HttpResponse<String> response, int status, String step) {
        if (response.statusCode() != status) {
            // The body is not printed: a token answer can carry a credential.
            throw new IllegalStateException("tenant B seed: " + step + " answered " + response.statusCode()
                    + " (expected " + status + ")");
        }
    }
}
