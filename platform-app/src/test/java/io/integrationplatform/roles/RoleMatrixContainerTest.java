package io.integrationplatform.roles;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * E4-F1-S3 (T-099; ADR-007 Decision 5; PRD section 3) through the REAL running application and its real endpoints: a
 * user holding only one role (rows of {@code role_grant}, never identity-provider roles) calls the config admin API
 * directly. The operations whose endpoints exist today are proven here; issuing a credential (E5), reading a message
 * timeline and replaying a dead letter (E13) are proven at the policy level ({@code RolePolicyTest}) until those
 * endpoints bind their operations. Bearer chain; the console session chain binds the same authentication and goes
 * through the same interceptor (identity.ConsoleLoginContainerTest and the ui screens run it with roles seeded).
 *
 * <p>The suite's decoder trusts the token text as the subject (token validation is proven elsewhere; the tenant and
 * the user still come from the directory through the real resolver). Container group (R03), PostgreSQL.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(RoleMatrixContainerTest.SuiteDecoder.class)
@ExtendWith(OutputCaptureExtension.class)
class RoleMatrixContainerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FLOWS = "/api/v1/admin/flows";
    private static final String ROLE_REFUSAL = "ROLE_NOT_GRANTED";
    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static RestrictedLogin login;
    private static UUID tenantId;
    private static String sourceSystem;
    /** role name (or NONE) -> the subject of a user holding exactly that role. */
    private static final Map<String, String> SUBJECTS = new LinkedHashMap<>();
    private static final Map<String, UUID> USER_IDS = new LinkedHashMap<>();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SuiteDecoder {

        @Bean
        @Primary
        JwtDecoder roleMatrixSuiteJwtDecoder() {
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
    }

    /** Instance phase (the T094 pattern): the application has migrated the schema by now. */
    @BeforeEach
    void seed() throws Exception {
        if (!SEEDED.compareAndSet(false, true)) {
            return;
        }
        tenantId = UUID.randomUUID();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            try (PreparedStatement tenant = owner.prepareStatement(
                    "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
                tenant.setString(1, tenantId.toString());
                tenant.setString(2, "tnt-roles-" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36));
                tenant.executeUpdate();
            }
            for (String role : List.of("TENANT_ADMIN", "CONFIGURATOR", "APPROVER", "SUPPORT", "READ_ONLY", "NONE")) {
                UUID userId = UUID.randomUUID();
                String subject = "sub-" + role.toLowerCase() + "-" + UUID.randomUUID();
                try (PreparedStatement user = owner.prepareStatement("INSERT INTO app_user (id, tenant_id, issuer,"
                        + " subject, status) VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')")) {
                    user.setString(1, userId.toString());
                    user.setString(2, tenantId.toString());
                    user.setString(3, PlatformTestWiring.OIDC_ISSUER.toString());
                    user.setString(4, subject);
                    user.executeUpdate();
                }
                if (!role.equals("NONE")) {
                    try (PreparedStatement grant = owner.prepareStatement(
                            "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, ?)")) {
                        grant.setString(1, tenantId.toString());
                        grant.setString(2, userId.toString());
                        grant.setString(3, role);
                        grant.executeUpdate();
                    }
                }
                SUBJECTS.put(role, subject);
                USER_IDS.put(role, userId);
            }
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        sourceSystem = "src-roles-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> system = call("TENANT_ADMIN", "POST", "/api/v1/admin/systems",
                "{\"code\":\"" + sourceSystem + "\",\"name\":\"role matrix source\",\"direction\":\"INBOUND\"}");
        assertThat(system.statusCode()).as("seed the source system: %s", system.body()).isEqualTo(201);
    }

    // Scenario Outline: The role matrix (rows whose endpoints exist today)

    @Test
    @DisplayName("Role matrix: a configurator saves a flow draft - allowed")
    void aConfiguratorSavesAFlowDraft() throws Exception {
        String flowId = newFlow();

        HttpResponse<String> saved = call("CONFIGURATOR", "PUT", FLOWS + "/" + flowId, flowBody());

        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(JSON.readTree(saved.body()).get("version").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Role matrix: an approver approves ANOTHER user's version - allowed")
    void anApproverApprovesAnothersVersion() throws Exception {
        String flowId = newFlow();
        submit(flowId);

        HttpResponse<String> approved = call("APPROVER", "POST", FLOWS + "/" + flowId + "/approve", null);

        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("Role matrix: a read-only user reads configuration - allowed")
    void aReadOnlyUserReadsConfiguration() throws Exception {
        String flowId = newFlow();

        HttpResponse<String> list = call("READ_ONLY", "GET", FLOWS, null);
        HttpResponse<String> one = call("READ_ONLY", "GET", FLOWS + "/" + flowId, null);

        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(list.body()).contains(flowId);
        assertThat(one.statusCode()).as(one.body()).isEqualTo(200);
    }

    // @negative @security Scenario Outline: Each role is refused outside its job (directly on the API)

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Refused: support saves a flow draft - 403, nothing written, audited")
    void supportCannotSaveAFlowDraft(CapturedOutput output) throws Exception {
        String flowId = newFlow();

        HttpResponse<String> refused = call("SUPPORT", "PUT", FLOWS + "/" + flowId, flowBody());

        assertRoleRefusal(refused);
        assertThat(versions(flowId)).as("no draft version written").isEqualTo(1);
        assertThat(output.getAll().lines().filter(line -> line.contains("event=role.denied")).toList())
                .anySatisfy(line -> assertThat(line).contains("operation=EDIT_CONFIGURATION",
                        "path=" + FLOWS + "/" + flowId, "user=" + USER_IDS.get("SUPPORT")));
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Refused: an approver approves their OWN version - 403 AUTHOR_IS_APPROVER from the real endpoint")
    void anApproverCannotApproveTheirOwnVersion() throws Exception {
        String flowId = pendingFlowAuthoredBy(USER_IDS.get("APPROVER"));

        HttpResponse<String> refused = call("APPROVER", "POST", FLOWS + "/" + flowId + "/approve", null);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(JSON.readTree(refused.body()).path("refusal").asString())
                .as("the role let the approver through; the record refused its own version")
                .isEqualTo("AUTHOR_IS_APPROVER");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Refused: read-only and approver cannot write a draft; a configurator cannot approve; support cannot read config")
    void eachRoleIsRefusedOutsideItsJob() throws Exception {
        String flowId = newFlow();
        submit(flowId);

        assertRoleRefusal(call("READ_ONLY", "PUT", FLOWS + "/" + flowId, flowBody()));
        assertRoleRefusal(call("APPROVER", "POST", FLOWS, flowBody()));
        assertRoleRefusal(call("CONFIGURATOR", "POST", FLOWS + "/" + flowId + "/approve", null));
        assertRoleRefusal(call("CONFIGURATOR", "POST", FLOWS + "/" + flowId + "/publish", null));
        assertRoleRefusal(call("SUPPORT", "GET", FLOWS, null));
        assertRoleRefusal(call("READ_ONLY", "POST", "/api/v1/admin/systems",
                "{\"code\":\"x-" + UUID.randomUUID() + "\",\"name\":\"n\",\"direction\":\"INBOUND\"}"));
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A bound user with NO role grant is refused every config operation but may read who they are")
    void aUserWithoutARoleIsRefused() throws Exception {
        assertRoleRefusal(call("NONE", "GET", FLOWS, null));
        assertRoleRefusal(call("NONE", "POST", FLOWS, flowBody()));
        HttpResponse<String> me = call("NONE", "GET", "/api/v1/me", null);
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(me.body()).contains(USER_IDS.get("NONE").toString());
    }

    @Test
    @DisplayName("A tenant admin may do everything in the tenant, approving included (PRD section 3)")
    void aTenantAdminMayApproveAndPublish() throws Exception {
        String flowId = newFlow(); // authored by the tenant admin
        bindMappingAndResnapshot(flowId);
        submit(flowId);

        HttpResponse<String> ownApproval = call("TENANT_ADMIN", "POST", FLOWS + "/" + flowId + "/approve", null);
        assertThat(JSON.readTree(ownApproval.body()).path("refusal").asString())
                .as("the admin passes the role check; never-own-version still holds on the record")
                .isEqualTo("AUTHOR_IS_APPROVER");
        assertThat(call("APPROVER", "POST", FLOWS + "/" + flowId + "/approve", null).statusCode()).isEqualTo(200);
        HttpResponse<String> published = call("TENANT_ADMIN", "POST", FLOWS + "/" + flowId + "/publish", null);
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static void assertRoleRefusal(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s %s -> %s", response.request().method(), response.request().uri(),
                response.body()).isEqualTo(403);
        assertThat(JSON.readTree(response.body()).path("refusal").asString()).isEqualTo(ROLE_REFUSAL);
    }

    /** T-190: binds the route's mapping to contract versions and re-captures the bundle (the pin check). */
    private void bindMappingAndResnapshot(String flowId) throws Exception {
        String routeId;
        String sourceVersion;
        String systemId;
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            routeId = ownerScalar(owner, "SELECT id::text FROM public.route WHERE interface_id = ?::uuid", flowId);
            sourceVersion = ownerScalar(owner,
                    "SELECT source_contract_version_id::text FROM public.interface_version"
                            + " WHERE interface_id = ?::uuid ORDER BY version DESC LIMIT 1", flowId);
            systemId = ownerScalar(owner, "SELECT s.id::text FROM public.system s WHERE s.code = ?",
                    "dst-" + sourceSystem);
            UUID contractId = UUID.nameUUIDFromBytes(
                    ("mapping-target-contract|" + systemId).getBytes(StandardCharsets.UTF_8));
            UUID versionId = UUID.nameUUIDFromBytes(
                    ("mapping-target-version|" + systemId).getBytes(StandardCharsets.UTF_8));
            ownerExec(owner, "INSERT INTO contract (id, tenant_id, system_id, code, role, name)"
                    + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'TARGET_REQUEST', ?)"
                    + " ON CONFLICT (tenant_id, id) DO NOTHING",
                    contractId.toString(), tenantId.toString(), systemId,
                    "map-target-" + contractId.toString().substring(0, 8), "mapping pin target contract");
            ownerExec(owner, "INSERT INTO contract_version (id, tenant_id, contract_id, version_label, status,"
                    + " json_schema_draft_2020_12, author_id)"
                    + " VALUES (?::uuid, ?::uuid, ?::uuid, '1.0', 'APPROVED', ?::jsonb, ?::uuid)"
                    + " ON CONFLICT (tenant_id, id) DO NOTHING",
                    versionId.toString(), tenantId.toString(), contractId.toString(),
                    "{\"type\":\"object\",\"properties\":{\"customer_code\":{\"type\":\"string\"}}}",
                    tenantId.toString());
        }
        String targetVersion = UUID.nameUUIDFromBytes(("mapping-target-version|" + systemId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        HttpResponse<String> bound = call("TENANT_ADMIN", "PUT",
                "/api/v1/admin/routes/" + routeId + "/mapping",
                "{\"fieldMappings\":[],\"compiledExpression\":\"$\",\"sourceContractVersionId\":\""
                        + sourceVersion + "\",\"targetContractVersionId\":\"" + targetVersion + "\"}");
        assertThat(bound.statusCode()).as("the mapping binds: %s", bound.body()).isEqualTo(200);
        HttpResponse<String> redraft = call("TENANT_ADMIN", "PUT", FLOWS + "/" + flowId, flowBody());
        assertThat(redraft.statusCode()).as("the bound bundle is re-captured: %s", redraft.body()).isEqualTo(200);
    }

    private static String ownerScalar(Connection owner, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int position = 0; position < values.length; position++) {
                statement.setString(position + 1, values[position]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("the binding premise query answers: %s", sql).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static void ownerExec(Connection owner, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int position = 0; position < values.length; position++) {
                statement.setObject(position + 1, values[position]);
            }
            statement.executeUpdate();
        }
    }

    private String newFlow() throws Exception {
        HttpResponse<String> created = call("TENANT_ADMIN", "POST", FLOWS, flowBody());
        assertThat(created.statusCode()).as("create a flow: %s", created.body()).isEqualTo(201);
        return JSON.readTree(created.body()).get("id").asString();
    }

    private void submit(String flowId) throws Exception {
        HttpResponse<String> submitted = call("TENANT_ADMIN", "POST", FLOWS + "/" + flowId + "/submit",
                "{\"changeNote\":\"role matrix\"}");
        assertThat(submitted.statusCode()).as("submit: %s", submitted.body()).isEqualTo(200);
    }

    private static String flowBody() {
        return "{\"businessKeyRule\":\"$.orderId\",\"sourceSystem\":\"" + sourceSystem
                + "\",\"destinations\":[\"dst-" + sourceSystem + "\"]}";
    }

    /** A flow whose PENDING_APPROVAL version names {@code author} - written as the owner (fixture SQL). */
    private String pendingFlowAuthoredBy(UUID author) throws Exception {
        String template = newFlow();
        UUID interfaceId = UUID.randomUUID();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            String contractVersion;
            try (PreparedStatement read = owner.prepareStatement("SELECT source_contract_version_id::text FROM"
                    + " interface_version WHERE interface_id = ?::uuid")) {
                read.setString(1, template);
                try (ResultSet rs = read.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    contractVersion = rs.getString(1);
                }
            }
            try (PreparedStatement flow = owner.prepareStatement("INSERT INTO interface (id, tenant_id, code, name,"
                    + " ordering, current_version) VALUES (?::uuid, ?::uuid, ?, ?, 'PER_KEY', 1)")) {
                flow.setString(1, interfaceId.toString());
                flow.setString(2, tenantId.toString());
                flow.setString(3, "flow-own-" + interfaceId);
                flow.setString(4, "own version");
                flow.executeUpdate();
            }
            try (PreparedStatement version = owner.prepareStatement("INSERT INTO interface_version (id, tenant_id,"
                    + " interface_id, version, status, business_key_rule, snapshot, source_contract_version_id,"
                    + " author_id, submitted_at) VALUES (?::uuid, ?::uuid, ?::uuid, 1, 'PENDING_APPROVAL', '$.orderId',"
                    + " '{\"interface\":{},\"routes\":[],\"mappings\":[]}'::jsonb, ?::uuid, ?::uuid, now())")) {
                version.setString(1, UUID.randomUUID().toString());
                version.setString(2, tenantId.toString());
                version.setString(3, interfaceId.toString());
                version.setString(4, contractVersion);
                version.setString(5, author.toString());
                version.executeUpdate();
            }
        }
        return interfaceId.toString();
    }

    private static int versions(String flowId) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); PreparedStatement count =
                owner.prepareStatement("SELECT count(*) FROM interface_version WHERE interface_id = ?::uuid")) {
            count.setString(1, flowId);
            try (ResultSet rs = count.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private HttpResponse<String> call(String role, String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + SUBJECTS.get(role));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
