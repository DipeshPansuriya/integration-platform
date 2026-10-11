package io.integrationplatform.e5.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * E5 lane A (T-105, T-106): the shared seed and HTTP helpers of the contract-version suites. Tenants, users and their
 * {@code role_grant} rows are written as the Flyway owner (the app login never writes the directory); every contract
 * row the suites assert on is written through the real admin API. The suites' decoder trusts the token text as the
 * subject (token validation is proven by the Keycloak suites); the tenant and the user still come from the directory
 * through the real resolver.
 */
final class E5ContractFixture {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String CONTRACTS = "/api/v1/admin/contracts";
    static final String VERSIONS = CONTRACTS + "/versions";
    static final String ROLE_REFUSAL = "ROLE_NOT_GRANTED";

    /** Target A's contract v1.0 (JSON Schema): {customer_code, order_number, total_amount}. */
    static final String TARGET_A_V1 = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
             "required":["customer_code","order_number","total_amount"],
             "properties":{"customer_code":{"type":"string"},"order_number":{"type":"string"},
                 "total_amount":{"type":"number"}}}""";

    /** Target B's contract v2.0 (OpenAPI 3.0): {clientId, referenceNo, invoiceValue}. */
    static final String TARGET_B_V2_OPENAPI = """
            {"openapi":"3.0.3","info":{"title":"Target B orders","version":"2.0"},
             "paths":{"/orders":{"post":{"requestBody":{"content":{"application/json":{"schema":
                 {"$ref":"#/components/schemas/TargetBOrder"}}}},
                 "responses":{"200":{"description":"accepted"}}}}},
             "components":{"schemas":{"TargetBOrder":{"type":"object",
                 "required":["clientId","referenceNo","invoiceValue"],
                 "properties":{"clientId":{"type":"string"},"referenceNo":{"type":"string"},
                     "invoiceValue":{"type":"number"}}}}}}""";

    /** One seeded tenant: role (or NONE) -> subject and user id. */
    record Tenant(UUID id, String code, Map<String, String> subjects, Map<String, UUID> userIds) {

        String subject(String role) {
            return subjects.get(role);
        }

        UUID userId(String role) {
            return userIds.get(role);
        }
    }

    private E5ContractFixture() {
    }

    /** The suites' decoder: the bearer text is the subject; the issuer and audience are the wiring's test values. */
    static JwtDecoder subjectDecoder() {
        return token -> {
            Instant now = Instant.now();
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .issuer(PlatformTestWiring.OIDC_ISSUER.toString())
                    .subject(token)
                    .audience(List.of(PlatformTestWiring.OIDC_AUDIENCE))
                    .issuedAt(now)
                    .expiresAt(now.plus(Duration.ofMinutes(5)))
                    .build();
        };
    }

    /** A tenant with one ACTIVE user per role, each holding exactly that role (role_grant rows, E4 T-099). */
    static Tenant seedTenant(PostgreSQLContainer postgres, String word, String... roles) throws SQLException {
        UUID tenantId = UUID.randomUUID();
        String code = "tnt-e5-" + word + "-" + shortId();
        Map<String, String> subjects = new LinkedHashMap<>();
        Map<String, UUID> userIds = new LinkedHashMap<>();
        try (Connection owner = PlatformTestWiring.ownerConnection(postgres)) {
            execute(owner, "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')",
                    tenantId.toString(), code);
            for (String role : roles) {
                UUID userId = UUID.randomUUID();
                String subject = "sub-e5-" + word + "-" + role.toLowerCase() + "-" + shortId();
                execute(owner, "INSERT INTO app_user (id, tenant_id, issuer, subject, status)"
                                + " VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')",
                        userId.toString(), tenantId.toString(), PlatformTestWiring.OIDC_ISSUER.toString(), subject);
                if (!role.equals("NONE")) {
                    execute(owner, "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, ?)",
                            tenantId.toString(), userId.toString(), role);
                }
                subjects.put(role, subject);
                userIds.put(role, userId);
            }
        }
        return new Tenant(tenantId, code, subjects, userIds);
    }

    static HttpResponse<String> call(int port, String subject, String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + subject);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Registers a target system through the real systems API (tenant admin). */
    static String registerTargetSystem(int port, Tenant tenant, String prefix) throws Exception {
        String code = prefix + "-" + shortId();
        HttpResponse<String> created = call(port, tenant.subject("TENANT_ADMIN"), "POST", "/api/v1/admin/systems",
                "{\"code\":\"" + code + "\",\"name\":\"" + code + " target\",\"direction\":\"OUTBOUND\"}");
        assertThat(created.statusCode()).as("register system %s: %s", code, created.body()).isEqualTo(201);
        return code;
    }

    /** The import request body (T-105): the document goes as a JSON object, or as text when {@code asText}. */
    static String importBody(String system, String role, String label, String format, String schemaName,
            String document, boolean asText) {
        ObjectNode body = JSON.createObjectNode();
        body.put("system", system);
        body.put("role", role);
        body.put("name", system + " " + role.toLowerCase());
        body.put("versionLabel", label);
        body.put("format", format);
        if (schemaName != null) {
            body.put("schemaName", schemaName);
        }
        if (asText) {
            body.put("document", document);
        } else {
            body.set("document", JSON.readTree(document));
        }
        return body.toString();
    }

    static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    /** Imports, asserting 201, and answers the new version's id. */
    static String importVersion(int port, String subject, String system, String label, String format,
            String document) throws Exception {
        HttpResponse<String> imported = call(port, subject, "POST", VERSIONS + "/import",
                importBody(system, "TARGET_REQUEST", label, format, null, document, false));
        assertThat(imported.statusCode()).as("import %s %s: %s", system, label, imported.body()).isEqualTo(201);
        return json(imported).path("id").asString();
    }

    static void submit(int port, String subject, String versionId) throws Exception {
        HttpResponse<String> submitted = call(port, subject, "POST", VERSIONS + "/" + versionId + "/submit",
                "{\"changeNote\":\"e5 lane A\"}");
        assertThat(submitted.statusCode()).as("submit %s: %s", versionId, submitted.body()).isEqualTo(200);
    }

    static HttpResponse<String> approve(int port, String subject, String versionId) throws Exception {
        return call(port, subject, "POST", VERSIONS + "/" + versionId + "/approve", null);
    }

    /** Import, submit (author) and approve (another user) - the whole walk to a usable version. */
    static String importApproved(int port, String author, String approver, String system, String label,
            String format, String document) throws Exception {
        String versionId = importVersion(port, author, system, label, format, document);
        submit(port, author, versionId);
        HttpResponse<String> approved = approve(port, approver, versionId);
        assertThat(approved.statusCode()).as("approve %s: %s", versionId, approved.body()).isEqualTo(200);
        return versionId;
    }

    static String scalar(PostgreSQLContainer postgres, String sql, String... values) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(postgres);
             PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("scalar query answers a row: %s", sql).isTrue();
                return rs.getString(1);
            }
        }
    }

    static void execute(Connection owner, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
