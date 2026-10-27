package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.e11.TwoTargetOrdersSeed;
import io.integrationplatform.identity.KeycloakRealmContainer;
import io.integrationplatform.identity.KeycloakRealmContainer.ClientCredentials;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T171 (follow-up of T141 gate triage): the identity-over-HTTP gate cases that need a real Keycloak and the whole
 * application but no tenant B browser - S1-A4, S1-A5, S1-A8 and S2-A3 - plus the machine-principal legs of S1-A7 and
 * S2-A2 ({@link #s1_a7_s2_a2_machine_leg_every_admin_operation_refuses_a_source_token}; the human-role matrix of those
 * two cases is NOT run here, so the gate keeps them open). Same wiring as S7-A3 (Postgres with the restricted login,
 * the pinned realm, the application on a random port). Container tag: runs only under {@code -Pcontainer}.
 *
 * <p>Every case carries a positive control (a valid token is accepted) so a refusal cannot be a broken fixture.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityGateHttpIdentityContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Container
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            "http://localhost/login/oauth2/code/keycloak", "http://localhost/");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final AtomicBoolean GRANTED = new AtomicBoolean();
    private static PlatformTestWiring.RestrictedLogin login;
    private static TwoTargetOrdersSeed.Tenant tenantA;
    private static TwoTargetOrdersSeed.Tenant tenantB;
    private static ClientCredentials sourceA;

    @LocalServerPort
    private int port;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KEYCLOAK.start(); // idempotent; the issuer must be known before the context starts
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        registry.add("CONSOLE_URL", () -> "/");
        registry.add("platform.config.destinations.allowed-hosts", () -> "*.e11.test");
        registry.add("ROUTING_CORE_ALLOWED_SCHEMES", () -> "http,https");
    }

    @BeforeEach
    void seedOnce() throws Exception {
        if (GRANTED.compareAndSet(false, true)) {
            PlatformTestWiring.grantApplicationRole(POSTGRES, login);
            sourceA = KEYCLOAK.createConfidentialClient("t171-a-" + UUID.randomUUID().toString().substring(0, 8), true);
            ClientCredentials sourceB = KEYCLOAK.createConfidentialClient(
                    "t171-b-" + UUID.randomUUID().toString().substring(0, 8), true);
            try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
                tenantA = TwoTargetOrdersSeed.onboard(owner, "alpha", "T171-CUST-A", sourceA.clientId(),
                        KEYCLOAK.issuerUri());
                tenantB = TwoTargetOrdersSeed.onboard(owner, "bravo", "T171-CUST-B", sourceB.clientId(),
                        KEYCLOAK.issuerUri());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The only routes a source credential may reach (IngestEndpointRoles); everything else under /api is swept. */
    private static final java.util.Set<String> INTAKE_ROUTES = java.util.Set.of(
            "/api/v1/interfaces/{interfaceCode}/messages", "/api/v1/interfaces/{interfaceCode}/messages:bulk");

    /** The realm's issuer URL asked for under the other loopback name, so Keycloak signs a different "iss". */
    private static String alternateHostIssuerBase() {
        java.net.URI issuer = java.net.URI.create(KEYCLOAK.issuerUri());
        String other = "127.0.0.1".equals(issuer.getHost()) ? "localhost" : "127.0.0.1";
        return issuer.getScheme() + "://" + other + ":" + issuer.getPort() + "/realms/" + KeycloakRealmContainer.REALM;
    }

    private String intakePath() {
        return "/api/v1/interfaces/" + TwoTargetOrdersSeed.INTERFACE_CODE + "/messages";
    }

    private HttpResponse<String> call(String method, String pathAndQuery, String bearer, String body,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .timeout(Duration.ofSeconds(30));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> intake(String bearer, String customerId) throws Exception {
        return call("POST", intakePath(), bearer,
                "{\"customerId\":\"" + customerId + "\",\"orderId\":\"T171-" + UUID.randomUUID() + "\",\"amount\":1}",
                Map.of("Content-Type", "application/json", "Idempotency-Key", UUID.randomUUID().toString(),
                        "X-Source-Contract-Version", TwoTargetOrdersSeed.SOURCE_CONTRACT_LABEL));
    }

    private String tokenOf(ClientCredentials source) {
        return KEYCLOAK.clientCredentialsToken(source.clientId(), source.secret());
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode claimsOf(String jwt) {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private static String unsignedOrHmac(String alg, String claimsJson, byte[] hmacKey) throws Exception {
        String signingInput = b64("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}") + "." + b64(claimsJson);
        if (hmacKey == null) {
            return signingInput + ".";
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
        return signingInput + "." + b64(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
    }

    private static String rs256WithOwnKey(String kid, String claimsJson) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String signingInput = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}") + "." + b64(claimsJson);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64(signature.sign());
    }

    private static long messageCount(UUID tenant) throws Exception {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                Statement st = owner.createStatement();
                ResultSet rs = st.executeQuery("select count(*) from public.message where tenant_id = '" + tenant + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ S1-A4

    /**
     * S1-A4: Machine-client grant posture (ADR-007 D3).
     * Expected: Source token issued with the platform API aud; ROPC and implicit refused (deprecated grants not enabled)
     */
    @Test
    void s1_a4_machine_client_grant_posture() throws Exception {
        // a machine token carries the platform API audience
        String token = tokenOf(sourceA);
        JsonNode aud = claimsOf(token).path("aud");
        List<String> audiences = new ArrayList<>();
        if (aud.isArray()) {
            aud.forEach(n -> audiences.add(n.asString()));
        } else {
            audiences.add(aud.asString());
        }
        assertThat(audiences).as("aud of a source token").contains(PlatformTestWiring.OIDC_AUDIENCE);
        // ROPC is refused for the source client (and no user password exists to guess either way)
        HttpResponse<String> ropc = KEYCLOAK.tokenEndpoint(Map.of("grant_type", "password", "client_id",
                sourceA.clientId(), "client_secret", sourceA.secret(), "username", "nobody", "password", "nothing"));
        assertThat(ropc.statusCode()).as("password grant: %s", ropc.body()).isIn(400, 401);
        assertThat(JSON.readTree(ropc.body()).path("error").asString()).isEqualTo("unauthorized_client");
        // implicit is refused at the authorization endpoint (error redirect or error page, never a token)
        HttpResponse<String> implicit = HTTP.send(HttpRequest.newBuilder(URI.create(KEYCLOAK.issuerUri()
                        + "/protocol/openid-connect/auth?response_type=token&client_id=" + sourceA.clientId()
                        + "&redirect_uri=http://localhost/&scope=openid")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(String.valueOf(implicit.headers().firstValue("Location").orElse("")) + implicit.body())
                .as("implicit flow response").doesNotContain("access_token=");
        // the pinned realm: no client of the realm has the deprecated grants enabled
        Path file = Files.exists(Path.of("../compose/keycloak/platform-realm.json"))
                ? Path.of("../compose/keycloak/platform-realm.json") : Path.of("compose/keycloak/platform-realm.json");
        for (JsonNode client : JSON.readTree(Files.readString(file)).get("clients")) {
            if (client.path("serviceAccountsEnabled").asBoolean()) {
                assertThat(client.path("directAccessGrantsEnabled").asBoolean())
                        .as("ROPC on machine client %s", client.path("clientId").asString()).isFalse();
                assertThat(client.path("implicitFlowEnabled").asBoolean())
                        .as("implicit on machine client %s", client.path("clientId").asString()).isFalse();
            }
        }
    }

    // ------------------------------------------------------------------ S1-A5

    /**
     * S1-A5: Token validation rules (ADR-007 D4): alg allow-list, aud, skew, kid.
     * Expected: Every token 401-final (alg=none/HS256 refused, aud required, skew bounded, unknown kid ..., rotated kid accepted)
     */
    @Test
    void s1_a5_token_validation_rules() throws Exception {
        String valid = tokenOf(sourceA);
        assertThat(intake(valid, "T171-CUST-A").statusCode()).as("positive control: a valid token").isEqualTo(202);

        JsonNode claims = claimsOf(valid);
        String claimsJson = JSON.writeValueAsString(claims);
        String[] parts = valid.split("\\.");
        Map<String, String> forged = new LinkedHashMap<>();
        forged.put("alg=none", unsignedOrHmac("none", claimsJson, null));
        forged.put("alg=HS256 keyed with a guessed secret", unsignedOrHmac("HS256", claimsJson,
                "secret".getBytes(StandardCharsets.UTF_8)));
        forged.put("alg=HS256 keyed with the public issuer URL", unsignedOrHmac("HS256", claimsJson,
                KEYCLOAK.issuerUri().getBytes(StandardCharsets.UTF_8)));
        forged.put("RS256 signed by an unknown key and kid", rs256WithOwnKey("unknown-kid-" + UUID.randomUUID(),
                claimsJson));
        forged.put("valid header+claims, signature stripped", parts[0] + "." + parts[1] + ".");
        forged.put("valid header+claims, signature replaced", parts[0] + "." + parts[1] + "."
                + b64(new byte[256]));
        // claims tampered after signing: another subject / an extended expiry under the original signature
        String tamperedClaims = claimsJson.replaceFirst("\"exp\":\\d+", "\"exp\":" + (Instant.now().getEpochSecond() + 86_400));
        forged.put("exp extended, original signature", parts[0] + "." + b64(tamperedClaims) + "." + parts[2]);
        forged.put("random text", "not-a-jwt");
        forged.put("empty bearer value", "x");
        for (Map.Entry<String, String> variant : forged.entrySet()) {
            HttpResponse<String> response = intake(variant.getValue(), "T171-CUST-A");
            assertThat(response.statusCode()).as("%s -> %s", variant.getKey(), response.body()).isEqualTo(401);
        }
        // a wrong issuer on a token the realm really signed (T201 / T171-2): Keycloak (start-dev, no fixed hostname)
        // writes the host the token was asked for into "iss", so asking for the token via 127.0.0.1 instead of
        // localhost gives a genuine signature (same realm key, same kid) over an issuer the backend does not trust.
        // Only the issuer check can refuse it; the forged-key case below it stays as the signature-only twin.
        String wrongIssuerToken = KEYCLOAK.clientCredentialsTokenVia(alternateHostIssuerBase(), sourceA.clientId(),
                sourceA.secret());
        assertThat(claimsOf(wrongIssuerToken).path("iss").asString()).as("the minted token carries a foreign issuer")
                .isNotEmpty().isNotEqualTo(KEYCLOAK.issuerUri());
        assertThat(JSON.readTree(Base64.getUrlDecoder().decode(wrongIssuerToken.split("\\.")[0])).path("kid").asString())
                .as("signed with the realm's own published key, so the signature check passes")
                .isEqualTo(JSON.readTree(Base64.getUrlDecoder().decode(parts[0])).path("kid").asString());
        assertThat(intake(wrongIssuerToken, "T171-CUST-A").statusCode()).as("validly signed, wrong issuer")
                .isEqualTo(401);
        // and a wrong issuer under a key the issuer never published (dies on the signature, kept as a twin)
        String wrongIssuer = claimsJson.replaceFirst("\"iss\":\"[^\"]*\"", "\"iss\":\"https://attacker.invalid/realms/platform\"");
        assertThat(intake(rs256WithOwnKey("attacker", wrongIssuer), "T171-CUST-A").statusCode())
                .as("wrong issuer and foreign key").isEqualTo(401);
        // token in the query string or a non-Bearer scheme is not a credential
        assertThat(call("POST", intakePath() + "?access_token=" + valid, null, "{}",
                Map.of("Content-Type", "application/json")).statusCode()).as("token in query string").isEqualTo(401);
        assertThat(call("POST", intakePath(), null, "{}", Map.of("Content-Type", "application/json",
                "Authorization", "Basic " + valid)).statusCode()).as("Basic scheme carrying the token").isEqualTo(401);
        assertThat(call("POST", intakePath(), null, "{}", Map.of("Content-Type", "application/json")).statusCode())
                .as("no credential at all").isEqualTo(401);
        // expiry: a token whose lifetime is 5 s is refused once lifetime + the validator's skew has passed
        Object previous = KEYCLOAK.realmRepresentation().path("accessTokenLifespan").asInt(300);
        KEYCLOAK.admin("PUT", "", Map.of("accessTokenLifespan", 5));
        try {
            String shortLived = tokenOf(sourceA);
            assertThat(intake(shortLived, "T171-CUST-A").statusCode()).as("short-lived token while fresh").isEqualTo(202);
            Instant until = Instant.now().plus(Duration.ofSeconds(150));
            int status = 202;
            while (status != 401 && Instant.now().isBefore(until)) {
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofSeconds(5).toNanos());
                status = intake(shortLived, "T171-CUST-A").statusCode();
            }
            assertThat(status).as("the expired token is refused within lifetime + skew (150 s limit)").isEqualTo(401);
        } finally {
            KEYCLOAK.admin("PUT", "", Map.of("accessTokenLifespan", previous));
        }
    }

    // ------------------------------------------------------------------ S1-A8

    /**
     * S1-A8: Default audience scope (ADR-007 D3 S-j).
     * Expected: First token accepted by the API; second 401
     */
    @Test
    void s1_a8_default_audience_scope() throws Exception {
        ClientCredentials withAudience = KEYCLOAK.createConfidentialClient(
                "t171-aud-" + UUID.randomUUID().toString().substring(0, 8), true);
        ClientCredentials withoutAudience = KEYCLOAK.createConfidentialClient(
                "t171-noaud-" + UUID.randomUUID().toString().substring(0, 8), false);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            TwoTargetOrdersSeed.onboard(owner, "aud-" + UUID.randomUUID().toString().substring(0, 6), "T171-AUD-1",
                    withAudience.clientId(), KEYCLOAK.issuerUri());
            TwoTargetOrdersSeed.onboard(owner, "noaud-" + UUID.randomUUID().toString().substring(0, 6), "T171-AUD-2",
                    withoutAudience.clientId(), KEYCLOAK.issuerUri());
        }
        assertThat(intake(tokenOf(withAudience), "T171-AUD-1").statusCode())
                .as("a client with the audience scope").isEqualTo(202);
        String noAudToken = tokenOf(withoutAudience);
        assertThat(claimsOf(noAudToken).toString()).as("the token really lacks the platform audience")
                .doesNotContain(PlatformTestWiring.OIDC_AUDIENCE);
        HttpResponse<String> refused = intake(noAudToken, "T171-AUD-2");
        assertThat(refused.statusCode()).as("a valid, registered client without the audience: %s", refused.body())
                .isEqualTo(401);
        // the realm default: new clients get the audience scope by default (S-j)
        HttpResponse<String> defaults = KEYCLOAK.admin("GET", "/default-default-client-scopes", null);
        assertThat(defaults.statusCode()).isEqualTo(200);
        assertThat(defaults.body()).as("realm default client scopes (a new client gets the audience)")
                .contains(KeycloakRealmContainer.AUDIENCE_SCOPE);
    }

    // ------------------------------------------------------------------ S1-A7 / S2-A2 machine leg

    /**
     * S1-A7 and S2-A2 (machine-principal leg ONLY): a source system's token is allowed one operation family (the
     * intake) and nothing else. The human role matrix is not exercised here and those two gate cases stay open.
     */
    @Test
    void s1_a7_s2_a2_machine_leg_every_admin_operation_refuses_a_source_token() throws Exception {
        String source = tokenOf(sourceA);
        List<String> swept = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            for (var pattern : info.getPathPatternsCondition().getPatterns()) {
                String raw = pattern.getPatternString();
                if (!raw.startsWith("/api/") || INTAKE_ROUTES.contains(raw)) {
                    continue; // only the intake routes a source legitimately reaches (S7-A3 covers them)
                }
                String path = raw.replaceAll("\\{[^/}]+}", UUID.randomUUID().toString());
                Iterable<org.springframework.web.bind.annotation.RequestMethod> methods =
                        info.getMethodsCondition().getMethods().isEmpty()
                                ? List.of(org.springframework.web.bind.annotation.RequestMethod.GET)
                                : info.getMethodsCondition().getMethods();
                for (var method : methods) {
                    HttpResponse<String> response = call(method.name(), path, source,
                            method.name().equals("GET") || method.name().equals("DELETE") ? null : "{}",
                            Map.of("Content-Type", "application/json"));
                    assertThat(response.statusCode()).as("source token on %s %s -> %s", method, raw, response.body())
                            .isEqualTo(403);
                    assertThat(response.body()).as("%s %s refusal names no tenant data", method, raw)
                            .doesNotContain(tenantA.id().toString()).doesNotContain(tenantB.id().toString());
                    swept.add(method + " " + raw);
                }
                HttpResponse<String> anonymous = call("GET", path, null, null, Map.of());
                assertThat(anonymous.statusCode()).as("no token on %s", raw).isIn(401, 403, 405);
            }
        }
        assertThat(swept).as("endpoints swept (the application's /api surface minus the intake)").hasSizeGreaterThan(20);
        // T201 (T171-1): the admin message family lives under /messages too; it must be in the sweep, not skipped
        assertThat(swept).as("the admin message endpoints are swept with a source token")
                .contains("GET /api/v1/admin/messages/{trackingId}/timeline", "GET /api/v1/admin/messages/failures",
                        "POST /api/v1/admin/messages/deliveries/{deliveryId}/replay",
                        "POST /api/v1/admin/messages/deliveries/{deliveryId}/retry")
                .noneMatch(entry -> INTAKE_ROUTES.stream().anyMatch(entry::endsWith));
    }

    // ------------------------------------------------------------------ S2-A3

    /**
     * S2-A3: Method and parameter tampering (IDOR pack bypass set).
     * Expected: No bypass: the tenant filter never depends on path shape or parameter order; every variant errors or 404s inside A
     * (machine-principal leg: tenant A's source credential against the intake and the admin paths).
     */
    @Test
    void s2_a3_method_and_parameter_tampering() throws Exception {
        String source = tokenOf(sourceA);
        long bBefore = messageCount(tenantB.id());
        long aBefore = messageCount(tenantA.id());
        Map<String, String> json = Map.of("Content-Type", "application/json", "Idempotency-Key", UUID.randomUUID().toString(),
                "X-Source-Contract-Version", TwoTargetOrdersSeed.SOURCE_CONTRACT_LABEL);
        String bodyB = "{\"customerId\":\"T171-CUST-B\",\"orderId\":\"T171-TAMPER\",\"amount\":1}";
        String bodyA = "{\"customerId\":\"T171-CUST-A\",\"orderId\":\"T171-TAMPER-A\",\"amount\":1}";
        int accepted = 0;
        // method tampering: verbs the intake does not declare never write
        for (String method : List.of("PUT", "PATCH", "DELETE", "GET", "OPTIONS")) {
            HttpResponse<String> response = call(method, intakePath(), source, "GET".equals(method) ? null : bodyA, json);
            // OPTIONS is answered by the framework (Allow header, no handler); every other verb must be refused
            assertThat(response.statusCode()).as("%s on the intake", method)
                    .isNotIn("OPTIONS".equals(method) ? List.of(201, 202, 204) : List.of(200, 201, 202, 204));
        }
        // override headers and parameter pollution: none turns A's credential into B's tenant
        List<Map<String, String>> headerVariants = List.of(
                merge(json, "X-HTTP-Method-Override", "PUT"), merge(json, "X-Tenant-Id", tenantB.id().toString()),
                merge(json, "Tenant", tenantB.id().toString()), merge(json, "X-Forwarded-Host", "tenant-b.example"));
        for (Map<String, String> headers : headerVariants) {
            HttpResponse<String> response = call("POST", intakePath() + "?tenantId=" + tenantB.id() + "&tenantId="
                    + tenantA.id(), source, bodyA, merge(headers, "Idempotency-Key", UUID.randomUUID().toString()));
            if (response.statusCode() / 100 == 2) {
                accepted++;
            }
        }
        // path-shape variants of the intake and of the admin paths
        for (String variant : List.of(intakePath() + "/", intakePath() + ";tenant=" + tenantB.id(), intakePath() + "//",
                intakePath().replace("/messages", "/%6dessages"), "/api/v1/../v1" + intakePath().substring("/api/v1".length()),
                "/api/v1/tenants/" + tenantB.id() + "/messages", "/api/v1/tenants/" + tenantB.id() + "/interfaces")) {
            HttpResponse<String> response = call("POST", variant, source, bodyA,
                    merge(json, "Idempotency-Key", UUID.randomUUID().toString()));
            if (response.statusCode() / 100 == 2) {
                accepted++;
            }
            assertThat(response.body()).as("%s names no tenant B data", variant).doesNotContain("T171-CUST-B");
        }
        // B's customer id in the body is a forgery for A's credential: 403, nothing written
        HttpResponse<String> forged = call("POST", intakePath(), source, bodyB,
                merge(json, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(forged.statusCode()).as(forged.body()).isEqualTo(403);
        // whatever was accepted landed in A, and nothing in B
        assertThat(messageCount(tenantB.id())).as("tenant B's messages after every variant").isEqualTo(bBefore);
        assertThat(messageCount(tenantA.id())).as("every accepted variant landed in tenant A").isEqualTo(aBefore + accepted);
    }

    private static Map<String, String> merge(Map<String, String> base, String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }
}
