package io.integrationplatform.e5.contract;

import static io.integrationplatform.e5.contract.E5ContractFixture.CONTRACTS;
import static io.integrationplatform.e5.contract.E5ContractFixture.ROLE_REFUSAL;
import static io.integrationplatform.e5.contract.E5ContractFixture.TARGET_A_V1;
import static io.integrationplatform.e5.contract.E5ContractFixture.TARGET_B_V2_OPENAPI;
import static io.integrationplatform.e5.contract.E5ContractFixture.VERSIONS;
import static io.integrationplatform.e5.contract.E5ContractFixture.approve;
import static io.integrationplatform.e5.contract.E5ContractFixture.call;
import static io.integrationplatform.e5.contract.E5ContractFixture.importApproved;
import static io.integrationplatform.e5.contract.E5ContractFixture.importBody;
import static io.integrationplatform.e5.contract.E5ContractFixture.importVersion;
import static io.integrationplatform.e5.contract.E5ContractFixture.json;
import static io.integrationplatform.e5.contract.E5ContractFixture.registerTargetSystem;
import static io.integrationplatform.e5.contract.E5ContractFixture.scalar;
import static io.integrationplatform.e5.contract.E5ContractFixture.submit;
import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.e5.contract.E5ContractFixture.Tenant;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * E5-F2-S1 (T-105) through the REAL running application and its real endpoints: versioned source and target contracts
 * imported from OpenAPI or JSON Schema, stored as rows side by side, approved by an approver or tenant admin (the author may approve their own version: single-user workflow),
 * tenant-scoped, retired versions still readable (ADR-011 Decisions 2 and 3), each new endpoint gated by its
 * {@code RolePolicy} operation and declaring its real statuses. Container group (R03): PostgreSQL. The Keycloak-issued
 * tokens of users and sources of both tenants run the same endpoints in the forged-tenant suite (E5ContractMatrix).
 *
 * <pre>
 * Scenario: Two contract versions side by side
 * Scenario: The author may approve their own contract version (single-user self-approval)
 * &#64;negative Scenario: An invalid schema is refused
 * &#64;negative &#64;tenant Scenario: Contracts are tenant-scoped
 * </pre>
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(E5ContractVersionsContainerTest.SuiteDecoder.class)
@ExtendWith(OutputCaptureExtension.class)
class E5ContractVersionsContainerTest {

    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static RestrictedLogin login;
    private static Tenant tenantA;
    private static Tenant tenantB;

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
        JwtDecoder e5ContractSuiteJwtDecoder() {
            return E5ContractFixture.subjectDecoder();
        }
    }

    @BeforeEach
    void seed() throws Exception {
        if (!SEEDED.compareAndSet(false, true)) {
            return;
        }
        tenantA = E5ContractFixture.seedTenant(POSTGRES, "a",
                "TENANT_ADMIN", "CONFIGURATOR", "APPROVER", "READ_ONLY", "SUPPORT");
        tenantB = E5ContractFixture.seedTenant(POSTGRES, "b", "TENANT_ADMIN", "CONFIGURATOR", "APPROVER");
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
    }

    // ------------------------------------------------------------------ Scenario: Two contract versions side by side

    @Test
    @DisplayName("Scenario: Two contract versions side by side - Target A v1.0 and Target B v2.0 stored as rows and listed")
    void twoContractVersionsSideBySide() throws Exception {
        String targetA = registerTargetSystem(port, tenantA, "tgt-a");
        String targetB = registerTargetSystem(port, tenantA, "tgt-b");
        String author = tenantA.subject("CONFIGURATOR");

        // Given Target A with contract v1.0 {customer_code, order_number, total_amount}
        String a10 = importVersion(port, author, targetA, "1.0", "JSON_SCHEMA", TARGET_A_V1);
        // ... and Target B on v1.0 too (the same shape), the version its v2.0 derives from
        String b10 = importVersion(port, author, targetB, "1.0", "JSON_SCHEMA", TARGET_A_V1);

        // When a configurator imports Target B's contract v2.0 {clientId, referenceNo, invoiceValue} from OpenAPI
        HttpResponse<String> imported = call(port, author, "POST", VERSIONS + "/import",
                importBody(targetB, "TARGET_REQUEST", "2.0", "OPENAPI", "TargetBOrder", TARGET_B_V2_OPENAPI, false));

        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(201);
        JsonNode b20 = json(imported);
        assertThat(b20.path("versionLabel").asString()).isEqualTo("2.0");
        assertThat(b20.path("status").asString()).isEqualTo("DRAFT");
        assertThat(b20.path("role").asString()).isEqualTo("TARGET_REQUEST");
        assertThat(b20.path("system").asString()).isEqualTo(targetB);
        assertThat(b20.path("derivedFromVersionId").asString()).as("lineage: v2.0 derives from v1.0").isEqualTo(b10);

        // Then both versions are stored as rows ...
        assertThat(scalar(POSTGRES, "SELECT string_agg(cv.version_label, ',' ORDER BY cv.version_label)"
                        + " FROM contract_version cv JOIN contract c ON c.id = cv.contract_id"
                        + " JOIN system s ON s.id = c.system_id WHERE s.code = ? AND c.role = 'TARGET_REQUEST'",
                targetB)).isEqualTo("1.0,2.0");
        assertThat(scalar(POSTGRES, "SELECT (json_schema_draft_2020_12 #> '{properties,clientId}' IS NOT NULL"
                + " AND json_schema_draft_2020_12 #> '{properties,referenceNo}' IS NOT NULL"
                + " AND json_schema_draft_2020_12 #> '{properties,invoiceValue}' IS NOT NULL"
                + " AND openapi_document IS NOT NULL)::text FROM contract_version WHERE id = ?::uuid",
                b20.path("id").asString())).isEqualTo("true");

        // ... and listed side by side: every contract of the tenant with its versions
        HttpResponse<String> listed = call(port, tenantA.subject("READ_ONLY"), "GET", CONTRACTS, null);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(labelsOf(json(listed), targetA)).containsExactly("1.0");
        assertThat(labelsOf(json(listed), targetB)).containsExactly("1.0", "2.0");

        // ... and one contract's versions side by side
        String contractB = b20.path("contractId").asString();
        HttpResponse<String> versionsOfB = call(port, author, "GET", CONTRACTS + "/" + contractB + "/versions", null);
        assertThat(versionsOfB.statusCode()).as(versionsOfB.body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        json(versionsOfB).path("versions").forEach(v -> ids.add(v.path("id").asString()));
        assertThat(ids).containsExactly(b10, b20.path("id").asString());

        // ... each version readable with its own schema
        HttpResponse<String> readB20 = call(port, author, "GET", VERSIONS + "/" + b20.path("id").asString(), null);
        assertThat(readB20.statusCode()).as(readB20.body()).isEqualTo(200);
        assertThat(json(readB20).path("role").asString()).isEqualTo("TARGET_REQUEST");
        assertThat(json(readB20).path("jsonSchema").path("properties").propertyNames())
                .containsExactlyInAnyOrder("clientId", "referenceNo", "invoiceValue");
        HttpResponse<String> readA10 = call(port, author, "GET", VERSIONS + "/" + a10, null);
        assertThat(json(readA10).path("jsonSchema").path("properties").propertyNames())
                .containsExactlyInAnyOrder("customer_code", "order_number", "total_amount");
    }

    @Test
    @Tag("negative")
    @DisplayName("@negative A version label the contract already has is refused with 409; nothing is stored")
    void aDuplicateLabelIsRefused() throws Exception {
        String target = registerTargetSystem(port, tenantA, "tgt-dup");
        String author = tenantA.subject("CONFIGURATOR");
        importVersion(port, author, target, "1.0", "JSON_SCHEMA", TARGET_A_V1);

        HttpResponse<String> again = call(port, author, "POST", VERSIONS + "/import",
                importBody(target, "TARGET_REQUEST", "1.0", "JSON_SCHEMA", null, TARGET_A_V1, false));

        assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
        assertThat(versionCount(target)).isEqualTo("1");
    }

    // ------------------------------------------------- the author may approve their own version (single-user workflow)

    @Test
    @DisplayName("Scenario: The author (a tenant admin) approves their own contract version; the audit names them")
    void theAuthorMayApproveTheirOwnVersion(CapturedOutput output) throws Exception {
        String target = registerTargetSystem(port, tenantA, "tgt-own");
        // Given a contract version authored by user U (a tenant admin: the role lets U approve in general)
        String author = tenantA.subject("TENANT_ADMIN");
        String versionId = importVersion(port, author, target, "2.0", "OPENAPI", TARGET_B_V2_OPENAPI);
        submit(port, author, versionId);

        // When U approves it
        HttpResponse<String> own = approve(port, author, versionId);

        // Then the approval succeeds and the version names U as its approver
        assertThat(own.statusCode()).as(own.body()).isEqualTo(200);
        assertThat(json(own).path("status").asString()).isEqualTo("APPROVED");
        assertThat(scalar(POSTGRES, "SELECT status || '/' || approver_id FROM contract_version WHERE id = ?::uuid",
                versionId)).isEqualTo("APPROVED/" + tenantA.userId("TENANT_ADMIN"));
        assertThat(output.getAll().lines().filter(line -> line.contains("event=contract.version.approve")).toList())
                .anySatisfy(line -> assertThat(line).contains("outcome=APPROVED",
                        "tenant=" + tenantA.id(), "actor=" + tenantA.userId("TENANT_ADMIN"), "version=" + versionId));

        // An approved version is immutable: approving it again (by anyone) is NOT_PENDING_APPROVAL
        HttpResponse<String> twice = approve(port, tenantA.subject("APPROVER"), versionId);
        assertThat(twice.statusCode()).as(twice.body()).isEqualTo(409);
        assertThat(json(twice).path("refusal").asString()).isEqualTo("NOT_PENDING_APPROVAL");
    }

    @Test
    @Tag("negative")
    @DisplayName("@negative A DRAFT is not approvable: 409 NOT_PENDING_APPROVAL, nothing changes")
    void aDraftIsNotApprovable() throws Exception {
        String target = registerTargetSystem(port, tenantA, "tgt-draft");
        String versionId = importVersion(port, tenantA.subject("CONFIGURATOR"), target, "1.0", "JSON_SCHEMA",
                TARGET_A_V1);

        HttpResponse<String> refused = approve(port, tenantA.subject("APPROVER"), versionId);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json(refused).path("refusal").asString()).isEqualTo("NOT_PENDING_APPROVAL");
        assertThat(scalar(POSTGRES, "SELECT status FROM contract_version WHERE id = ?::uuid", versionId))
                .isEqualTo("DRAFT");
    }

    // ---------------------------------------------------------------- @negative: an invalid schema is refused

    @Test
    @Tag("negative")
    @DisplayName("@negative Scenario: An invalid schema is refused with the parser's errors and nothing is stored")
    void anInvalidSchemaIsRefused() throws Exception {
        String target = registerTargetSystem(port, tenantA, "tgt-bad");
        String author = tenantA.subject("CONFIGURATOR");
        String before = contractRows();

        HttpResponse<String> badOpenApi = call(port, author, "POST", VERSIONS + "/import",
                importBody(target, "TARGET_REQUEST", "1.0", "OPENAPI", null, "{\"openapi\":\"3.0.3\",\"paths\":{}}",
                        false));
        HttpResponse<String> badSchema = call(port, author, "POST", VERSIONS + "/import",
                importBody(target, "TARGET_REQUEST", "1.0", "JSON_SCHEMA", null, "{\"type\":12}", false));
        HttpResponse<String> notParsable = call(port, author, "POST", VERSIONS + "/import",
                importBody(target, "TARGET_REQUEST", "1.0", "OPENAPI", null, "openapi: [3.0", true));

        for (HttpResponse<String> refused : List.of(badOpenApi, badSchema, notParsable)) {
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
            assertThat(json(refused).path("parserErrors").isArray()).as(refused.body()).isTrue();
            assertThat(json(refused).path("parserErrors").size()).as(refused.body()).isPositive();
        }
        assertThat(json(badOpenApi).path("parserErrors").toString()).contains("info");
        assertThat(contractRows()).as("nothing stored: no contract, no version").isEqualTo(before);
    }

    // ---------------------------------------------------------------- @negative @tenant: contracts are tenant-scoped

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("@negative @tenant Scenario: Contracts are tenant-scoped - tenant B reading tenant A's ids gets 404")
    void contractsAreTenantScoped() throws Exception {
        String target = registerTargetSystem(port, tenantA, "tgt-scope");
        String versionId = importVersion(port, tenantA.subject("CONFIGURATOR"), target, "1.0", "JSON_SCHEMA",
                TARGET_A_V1);
        submit(port, tenantA.subject("CONFIGURATOR"), versionId);
        String contractId = scalar(POSTGRES, "SELECT contract_id::text FROM contract_version WHERE id = ?::uuid",
                versionId);
        String b = tenantB.subject("CONFIGURATOR");

        HttpResponse<String> readVersion = call(port, b, "GET", VERSIONS + "/" + versionId, null);
        HttpResponse<String> readVersions = call(port, b, "GET", CONTRACTS + "/" + contractId + "/versions", null);
        HttpResponse<String> approveForeign = approve(port, tenantB.subject("APPROVER"), versionId);
        HttpResponse<String> listB = call(port, b, "GET", CONTRACTS, null);

        assertThat(readVersion.statusCode()).as(readVersion.body()).isEqualTo(404);
        assertThat(readVersions.statusCode()).as(readVersions.body()).isEqualTo(404);
        assertThat(approveForeign.statusCode()).as(approveForeign.body()).isEqualTo(404);
        for (HttpResponse<String> response : List.of(readVersion, readVersions, approveForeign, listB)) {
            assertThat(response.body()).doesNotContain(versionId, contractId, target, tenantA.id().toString());
        }
        assertThat(listB.statusCode()).as(listB.body()).isEqualTo(200);
        assertThat(scalar(POSTGRES, "SELECT status FROM contract_version WHERE id = ?::uuid", versionId))
                .as("tenant B's approval reached nothing").isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("ADR-011 D3 rule 4: a RETIRED version stays readable and listed (no new pins, never unreadable)")
    void aRetiredVersionStaysReadable() throws Exception {
        String target = registerTargetSystem(port, tenantA, "tgt-ret");
        String versionId = importApproved(port, tenantA.subject("CONFIGURATOR"), tenantA.subject("APPROVER"),
                target, "1.0", "JSON_SCHEMA", TARGET_A_V1);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            // the retire gate (0 non-final deliveries pin it) belongs to the delivery epic; the row state is what matters
            E5ContractFixture.execute(owner, "UPDATE contract_version SET status = 'RETIRED' WHERE id = ?::uuid",
                    versionId);
        }

        HttpResponse<String> read = call(port, tenantA.subject("READ_ONLY"), "GET", VERSIONS + "/" + versionId, null);
        HttpResponse<String> listed = call(port, tenantA.subject("READ_ONLY"), "GET", CONTRACTS, null);

        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(json(read).path("status").asString()).isEqualTo("RETIRED");
        assertThat(json(read).path("jsonSchema").path("properties").has("customer_code")).isTrue();
        assertThat(listed.body()).contains(versionId, "RETIRED");
    }

    // ---------------------------------------------------------------- role checks (E4 T-099 policy on the new endpoints)

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Roles: read-only lists but cannot import; a configurator cannot approve; support cannot read")
    void eachNewEndpointIsGatedByItsOperation() throws Exception {
        String target = registerTargetSystem(port, tenantA, "tgt-roles");
        String versionId = importVersion(port, tenantA.subject("CONFIGURATOR"), target, "1.0", "JSON_SCHEMA",
                TARGET_A_V1);
        submit(port, tenantA.subject("CONFIGURATOR"), versionId);
        String contractId = scalar(POSTGRES, "SELECT contract_id::text FROM contract_version WHERE id = ?::uuid",
                versionId);

        assertRoleRefusal(call(port, tenantA.subject("READ_ONLY"), "POST", VERSIONS + "/import",
                importBody(target, "TARGET_REQUEST", "9.0", "JSON_SCHEMA", null, TARGET_A_V1, false)));
        assertRoleRefusal(approve(port, tenantA.subject("CONFIGURATOR"), versionId));
        assertRoleRefusal(call(port, tenantA.subject("SUPPORT"), "GET", CONTRACTS, null));
        assertRoleRefusal(call(port, tenantA.subject("SUPPORT"), "GET", CONTRACTS + "/" + contractId + "/versions",
                null));

        assertThat(call(port, tenantA.subject("READ_ONLY"), "GET", CONTRACTS, null).statusCode()).isEqualTo(200);
        assertThat(call(port, tenantA.subject("APPROVER"), "GET", CONTRACTS + "/" + contractId + "/versions", null)
                .statusCode()).isEqualTo(200);
        assertThat(versionCount(target)).as("the refused import stored nothing").isEqualTo("1");
        assertThat(scalar(POSTGRES, "SELECT status FROM contract_version WHERE id = ?::uuid", versionId))
                .as("the refused approval changed nothing").isEqualTo("PENDING_APPROVAL");
    }

    // ---------------------------------------------------------------- every new endpoint declares its real statuses

    @Test
    @DisplayName("The new contract endpoints declare every status they answer (the ConfigAdminDeclaredResponses rule)")
    void theNewEndpointsDeclareTheirRealStatuses() throws Exception {
        HttpResponse<String> answer = call(port, tenantA.subject("READ_ONLY"), "GET", "/v3/api-docs", null);
        assertThat(answer.statusCode()).isEqualTo(200);
        JsonNode paths = json(answer).path("paths");
        Map<String, Set<String>> expected = Map.of(
                "post " + VERSIONS + "/import", Set.of("201", "400", "403", "409"),
                "get " + CONTRACTS, Set.of("200", "403"),
                "get " + CONTRACTS + "/{contractId}/versions", Set.of("200", "403", "404"),
                "post " + VERSIONS + "/{versionId}/approve", Set.of("200", "403", "404", "409"),
                "get " + VERSIONS + "/{versionId}", Set.of("200", "403", "404"));

        List<String> mismatches = new ArrayList<>();
        expected.forEach((operation, statuses) -> {
            String[] methodAndPath = operation.split(" ", 2);
            JsonNode responses = paths.path(methodAndPath[1]).path(methodAndPath[0]).path("responses");
            Set<String> declared = new TreeSet<>(responses.propertyNames());
            if (!declared.equals(new TreeSet<>(statuses))) {
                mismatches.add(operation + " declares " + declared + ", really answers " + new TreeSet<>(statuses));
            }
            for (String status : statuses) {
                JsonNode content = responses.path(status).path("content");
                if (!content.isObject() || content.isEmpty()) {
                    mismatches.add(operation + " " + status + " declares no body schema");
                }
            }
        });
        JsonNode importRefusal = paths.path(VERSIONS + "/import").path("post").path("responses").path("400");
        if (!importRefusal.toString().contains("ContractImportErrorBody")) {
            mismatches.add("the import's 400 does not name ContractImportErrorBody (error + parserErrors)");
        }
        assertThat(mismatches).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static void assertRoleRefusal(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s %s -> %s", response.request().method(), response.request().uri(),
                response.body()).isEqualTo(403);
        assertThat(json(response).path("refusal").asString()).isEqualTo(ROLE_REFUSAL);
    }

    private static List<String> labelsOf(JsonNode listing, String systemCode) {
        List<String> labels = new ArrayList<>();
        for (JsonNode contract : listing.path("contracts")) {
            if (contract.path("system").asString().equals(systemCode)) {
                contract.path("versions").forEach(v -> labels.add(v.path("versionLabel").asString()));
            }
        }
        return labels;
    }

    private static String versionCount(String systemCode) throws Exception {
        return scalar(POSTGRES, "SELECT count(*)::text FROM contract_version cv JOIN contract c ON c.id = cv.contract_id"
                + " JOIN system s ON s.id = c.system_id WHERE s.code = ?", systemCode);
    }

    private static String contractRows() throws Exception {
        return scalar(POSTGRES, "SELECT (SELECT count(*) FROM contract) || '|' || (SELECT count(*) FROM contract_version)");
    }
}
