package io.integrationplatform.config.e3;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The admin-API contract lane A's suites assert (T-016, T-092, T-093): the endpoints, field names and status
 * the scenarios name, in the platform's one path convention ({@code /api/v1/...}, the common API's; the tenant
 * never travels in the path - ADR-011 Decision 4).
 *
 * <p>The exact endpoint names are the design nodes the flow admin API (T-015, owner lane C) and the lifecycle
 * API (T-019, owner lane C) define; nothing outside those two tasks pins them (the recreated document set holds
 * no api-contract file any more). Until they land, every call answers 404 and that named mismatch IS the
 * committed RED of the red-first lane; when they land with different names, the mismatch the suites report is
 * the contract drift exactly the T-094 typed-client contract test exists to surface. The calls are real HTTP on
 * a real port - no mock is involved anywhere (the scenario's core guarantee).
 */
final class ConfigAdminApi {

    static final String FLOWS = "/api/v1/admin/flows";
    static final String SYSTEMS = "/api/v1/admin/systems";
    static final String CONTRACT_VERSIONS = "/api/v1/admin/contracts/versions";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ConfigAdminApi() {
    }

    /** One real HTTP response. */
    record Response(int status, String body) {

        JsonNode json() {
            return JsonMapper.builder().build().readTree(body());
        }
    }

    static String baseUrl(int port) {
        return "http://localhost:" + port;
    }

    /** The flow-create body: source system S, destinations T1 and T2, the mandatory business-key rule. */
    static String createFlow(String sourceSystem, List<String> destinations, String businessKeyRulePath) {
        ObjectNode json = JSON.createObjectNode();
        json.put("sourceSystem", sourceSystem);
        json.set("destinations", namesArray(destinations));
        json.put("businessKeyRule", businessKeyRulePath);
        json.put("ordering", "PER_KEY");
        return JSON.writeValueAsString(json);
    }

    static String createFlowWithoutBusinessKeyRule(String sourceSystem, List<String> destinations) {
        ObjectNode json = JSON.createObjectNode();
        json.put("sourceSystem", sourceSystem);
        json.set("destinations", namesArray(destinations));
        return JSON.writeValueAsString(json);
    }

    private static tools.jackson.databind.node.ArrayNode namesArray(List<String> names) {
        tools.jackson.databind.node.ArrayNode array = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        names.forEach(array::add);
        return array;
    }

    static String createSystem(String code, String name, String direction) {
        ObjectNode json = JSON.createObjectNode();
        json.put("code", code);
        json.put("name", name);
        json.put("direction", direction);
        return JSON.writeValueAsString(json);
    }

    /** The sourceSchema the ADR-011 draft-save contract keeps on the draft-save API (Decision 2). */
    static String saveDraft(String systemCode, String sourceSchemaJson) {
        ObjectNode json = JSON.createObjectNode();
        json.put("system", systemCode);
        json.put("role", "SOURCE_REQUEST");
        json.put("name", "draft-" + UUID.randomUUID());
        ObjectNode schema = JSON.createObjectNode();
        schema.setAll((ObjectNode) JSON.readTree(sourceSchemaJson));
        json.set("sourceSchema", schema);
        return JSON.writeValueAsString(json);
    }

    static String saveDraftMissingSourceSchema(String systemCode) {
        ObjectNode json = JSON.createObjectNode();
        json.put("system", systemCode);
        json.put("role", "SOURCE_REQUEST");
        json.put("name", "draft-" + UUID.randomUUID());
        return JSON.writeValueAsString(json);
    }

    static String submit(String changeNote) {
        ObjectNode json = JSON.createObjectNode();
        json.put("changeNote", changeNote);
        return JSON.writeValueAsString(json);
    }

    // ------------------------------------------------------------------ real HTTP

    static Response call(String method, String url, String bearer, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json");
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException("the real HTTP call to the running system failed: " + url, e);
        }
    }

    // ------------------------------------------------------------------ the bearer of "a configurator" (ADR-007: the tenant travels in the credential)

    /**
     * An RS256 access token signed with the suite's in-memory key pair (no key material in any file, R07,
     * generated per run). The claims mirror what ADR-007 Decision 4 validates (issuer, audience, exp, nbf) and
     * what the denial suite binds tenants by: the subject of a directory app_user of the named tenant.
     */
    static final class Bearer {

        private final KeyPair key;

        Bearer() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                key = generator.generateKeyPair();
            } catch (Exception e) {
                throw new IllegalStateException("could not create the suite's signing key", e);
            }
        }

        String forSubject(String subject) {
            Instant now = Instant.now();
            String header = base64Url("{\"alg\":\"RS256\"}");
            String claims = "{\"iss\":\"" + E3ConfigDatabase.OIDC_ISSUER + "\",\"sub\":\"" + subject
                    + "\",\"aud\":[\"" + E3ConfigDatabase.OIDC_AUDIENCE + "\"]"
                    + ",\"exp\":" + now.plusSeconds(300).getEpochSecond()
                    + ",\"nbf\":" + now.minusSeconds(5).getEpochSecond()
                    + ",\"iat\":" + now.getEpochSecond()
                    + ",\"jti\":\"" + UUID.randomUUID() + "\"}";
            String payload = base64Url(claims);
            try {
                Signature signer = Signature.getInstance("SHA256withRSA");
                signer.initSign(key.getPrivate());
                signer.update((header + "." + payload).getBytes(StandardCharsets.UTF_8));
                String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
                return header + "." + payload + "." + signature;
            } catch (Exception e) {
                throw new IllegalStateException("could not sign the suite's access token", e);
            }
        }

        private static String base64Url(String value) {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}