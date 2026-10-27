package io.integrationplatform.e5.contract;

import static io.integrationplatform.e5.contract.E5ContractFixture.TARGET_A_V1;
import static io.integrationplatform.e5.contract.E5ContractFixture.approve;
import static io.integrationplatform.e5.contract.E5ContractFixture.call;
import static io.integrationplatform.e5.contract.E5ContractFixture.importVersion;
import static io.integrationplatform.e5.contract.E5ContractFixture.registerTargetSystem;
import static io.integrationplatform.e5.contract.E5ContractFixture.scalar;
import static io.integrationplatform.e5.contract.E5ContractFixture.shortId;
import static io.integrationplatform.e5.contract.E5ContractFixture.submit;
import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.system.application.DbAccountWidthCheck;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import io.integrationplatform.e5.contract.E5ContractFixture.Tenant;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
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

/**
 * PR 39 follow-up (single-user self-approval, V2_4_1420): the three SECURITY DEFINER approval functions were rewritten,
 * and the one thing a future edit must never drop is their {@code tenant_id = current_setting('app.tenant_id')}
 * predicate. Tenant B's TENANT_ADMIN holds the approve role in B, so the role check lets the call through; only tenant
 * scoping stands between B and A's pending versions.
 *
 * <pre>
 * &#64;negative &#64;tenant Scenario: Another tenant's admin cannot approve a pending version
 *   Given tenant A has a flow version, a contract version and a DB-table request, each PENDING_APPROVAL
 *   When tenant B's TENANT_ADMIN approves each one by its id through the API
 *   Then every answer is 404 and no id or tenant A text is in the body
 *   And the three rows stay PENDING_APPROVAL with no approver or decider
 *   And no approval audit event and no refusal record exists for tenant A
 *   And tenant A's own admin then approves the same three (the premise holds: the rows were approvable)
 *
 * &#64;negative &#64;tenant Scenario: The definer functions themselves are tenant-scoped
 *   Given a session stamped as tenant B (the application login, no API in front)
 *   When it calls config_approve_version, config_approve_contract_version and config_decide_db_table_approval
 *     with tenant A's ids
 *   Then the answers are VERSION_NOT_FOUND, VERSION_NOT_FOUND and NOT_FOUND and the rows are unchanged
 * </pre>
 *
 * <p>The API walk alone is not enough: the services look the row up through the tenant filter first, so a definer
 * function that lost its predicate would still be hidden behind a 404. The second scenario calls the functions
 * directly, the way a service bug or an injected id would.
 */
@Tag("container")
@Tag("negative")
@Tag("tenant")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(CrossTenantApprovalContainerTest.SuiteWiring.class)
class CrossTenantApprovalContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final String SETTINGS =
            "{\"host\":\"db.invalid\",\"port\":5432,\"database\":\"xt\",\"user\":\"xt_narrow\"}";

    private static RestrictedLogin login;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SuiteWiring {

        @Bean
        @Primary
        JwtDecoder crossTenantApprovalJwtDecoder() {
            return E5ContractFixture.subjectDecoder();
        }

        /** The partner-database width probe is the one thing faked (a narrow account): tenant A's own approval passes it. */
        @Bean
        @Primary
        DbAccountWidthCheck crossTenantApprovalNarrowWidthCheck() {
            return (systemId, settings) -> Optional.empty();
        }
    }

    /** Tenant A's three pending things, by id. */
    private record Pending(String flowId, String flowVersionId, String contractVersionId, String systemId,
            String tableId) {
    }

    // ------------------------------------------------------------------ through the API

    @Test
    @DisplayName("@negative @tenant Another tenant's TENANT_ADMIN approving tenant A's pending flow, contract and"
            + " DB-table gets 404: nothing changes, no audit event, no refusal record")
    void anotherTenantsAdminCannotApproveThroughTheApi() throws Exception {
        Tenant a = seed("xa", "TENANT_ADMIN", "CONFIGURATOR", "APPROVER");
        Tenant b = seed("xb", "TENANT_ADMIN");
        Pending pending = pendingOf(a);
        String bAdmin = b.subject("TENANT_ADMIN");

        HttpResponse<String> flow = call(port, bAdmin, "POST",
                "/api/v1/admin/flows/" + pending.flowId() + "/approve", "{}");
        HttpResponse<String> contract = approve(port, bAdmin, pending.contractVersionId());
        HttpResponse<String> table = call(port, bAdmin, "POST", "/api/v1/admin/systems/" + pending.systemId()
                + "/approved-tables/" + pending.tableId() + "/approve", "{}");

        for (HttpResponse<String> answer : java.util.List.of(flow, contract, table)) {
            assertThat(answer.statusCode()).as("%s -> %s", answer.request().uri(), answer.body()).isEqualTo(404);
            assertThat(answer.body()).as("no id or text of tenant A in the answer")
                    .doesNotContain(pending.flowId(), pending.flowVersionId(), pending.contractVersionId(),
                            pending.systemId(), pending.tableId(), a.id().toString(), a.code());
        }
        assertUntouched(pending, a);
        assertThat(approvalAuditAndRefusals(b)).as("nothing recorded for tenant B either").isEqualTo("0|0");

        // premise: the same three rows are approvable by tenant A's own admin, so the 404s above were the tenant, not state
        String aAdmin = a.subject("TENANT_ADMIN");
        assertThat(call(port, aAdmin, "POST", "/api/v1/admin/flows/" + pending.flowId() + "/approve", "{}")
                .statusCode()).isEqualTo(200);
        assertThat(approve(port, aAdmin, pending.contractVersionId()).statusCode()).isEqualTo(200);
        assertThat(call(port, aAdmin, "POST", "/api/v1/admin/systems/" + pending.systemId() + "/approved-tables/"
                + pending.tableId() + "/approve", "{}").statusCode()).isEqualTo(200);
        assertThat(approvalAuditAndRefusals(a)).as("tenant A's own three approvals: three events, no refusal")
                .isEqualTo("3|0");
    }

    // ------------------------------------------------------------------ the functions, no API in front

    @Test
    @DisplayName("@negative @tenant The three definer functions called from a session stamped as tenant B answer"
            + " not-found for tenant A's ids and change nothing")
    void theDefinerFunctionsAreTenantScoped() throws Exception {
        Tenant a = seed("fa", "TENANT_ADMIN", "CONFIGURATOR", "APPROVER");
        Tenant b = seed("fb", "TENANT_ADMIN");
        Pending pending = pendingOf(a);
        UUID bAdmin = b.userId("TENANT_ADMIN");

        try (Connection session = stampedAs(b.id(), bAdmin)) {
            assertThat(callFunction(session, "select public.config_approve_version(?::uuid, ?::uuid)",
                    pending.flowVersionId(), bAdmin.toString())).isEqualTo("VERSION_NOT_FOUND");
            assertThat(callFunction(session, "select public.config_approve_contract_version(?::uuid, ?::uuid)",
                    pending.contractVersionId(), bAdmin.toString())).isEqualTo("VERSION_NOT_FOUND");
            assertThat(callFunction(session, "select public.config_decide_db_table_approval(?::uuid, 'APPROVE')",
                    pending.tableId())).isEqualTo("NOT_FOUND");
        }

        assertUntouched(pending, a);
    }

    // ------------------------------------------------------------------ fixtures and reads

    private Tenant seed(String word, String... roles) throws Exception {
        Tenant tenant = E5ContractFixture.seedTenant(POSTGRES, word, roles);
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        return tenant;
    }

    /** Tenant A: one flow version, one contract version and one DB-table request, each PENDING_APPROVAL. */
    private Pending pendingOf(Tenant a) throws Exception {
        UUID author = a.userId("CONFIGURATOR");
        String contractVersion = importVersion(port, a.subject("CONFIGURATOR"),
                registerTargetSystem(port, a, "tgt-xt"), "1.0", "JSON_SCHEMA", TARGET_A_V1);
        submit(port, a.subject("CONFIGURATOR"), contractVersion);

        String flowId = UUID.randomUUID().toString();
        String system = UUID.randomUUID().toString();
        String tableId = UUID.randomUUID().toString();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            E5ContractFixture.execute(owner, "INSERT INTO public.interface (id, tenant_id, code, name)"
                    + " VALUES (?::uuid, ?::uuid, ?, 'cross-tenant flow')", flowId, a.id().toString(),
                    "flow-xt-" + shortId());
            E5ContractFixture.execute(owner, "INSERT INTO public.interface_version (tenant_id, interface_id, version,"
                    + " status, business_key_rule, snapshot, source_contract_version_id, author_id)"
                    + " VALUES (?::uuid, ?::uuid, 1, 'PENDING_APPROVAL', '$.id', '{}'::jsonb, ?::uuid, ?::uuid)",
                    a.id().toString(), flowId, contractVersion, author.toString());
            E5ContractFixture.execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction)"
                    + " VALUES (?::uuid, ?::uuid, ?, 'XT DB', 'TARGET')", system, a.id().toString(),
                    "sys-xt-" + shortId());
            E5ContractFixture.execute(owner, "UPDATE public.system SET type = 'DB', settings = ?::jsonb"
                    + " WHERE id = ?::uuid", SETTINGS, system);
            E5ContractFixture.execute(owner, "INSERT INTO public.db_target_approved_table (id, tenant_id, system_id,"
                    + " table_name, status, requested_by, target_hash) VALUES (?::uuid, ?::uuid, ?::uuid, 'xt_orders',"
                    + " 'PENDING_APPROVAL', ?, public.db_target_fingerprint(?::jsonb))", tableId, a.id().toString(),
                    system, author.toString(), SETTINGS);
        }
        String flowVersionId = scalar(POSTGRES, "SELECT id::text FROM interface_version WHERE interface_id = ?::uuid",
                flowId);
        return new Pending(flowId, flowVersionId, contractVersion, system, tableId);
    }

    /** The three rows are still pending with nobody recorded as approver or decider, and A has no approval trail. */
    private static void assertUntouched(Pending pending, Tenant a) throws Exception {
        assertThat(scalar(POSTGRES, "SELECT status || '/' || coalesce(approver_id::text, '-') FROM interface_version"
                + " WHERE id = ?::uuid", pending.flowVersionId())).as("flow version")
                .isEqualTo("PENDING_APPROVAL/-");
        assertThat(scalar(POSTGRES, "SELECT status || '/' || coalesce(approver_id::text, '-') FROM contract_version"
                + " WHERE id = ?::uuid", pending.contractVersionId())).as("contract version")
                .isEqualTo("PENDING_APPROVAL/-");
        assertThat(scalar(POSTGRES, "SELECT status || '/' || coalesce(decided_by, '-') FROM db_target_approved_table"
                + " WHERE id = ?::uuid", pending.tableId())).as("DB-table request").isEqualTo("PENDING_APPROVAL/-");
        assertThat(approvalAuditAndRefusals(a)).as("no approval audit event and no refusal record for tenant A")
                .isEqualTo("0|0");
    }

    /** {@code approval audit events | refusal records} of the tenant, read as the owner. */
    private static String approvalAuditAndRefusals(Tenant tenant) throws Exception {
        return scalar(POSTGRES, "SELECT (SELECT count(*) FROM audit_event WHERE tenant_id = ?::uuid AND action IN (?, ?, ?))"
                + " || '|' || (SELECT count(*) FROM config_version_refusal WHERE tenant_id = ?::uuid)",
                tenant.id().toString(), ConfigAuditActions.FLOW_APPROVED, ConfigAuditActions.CONTRACT_VERSION_APPROVED,
                ConfigAuditActions.DB_TABLE_APPROVED, tenant.id().toString());
    }

    /** A raw application-login session stamped the way a request stamps it (tenant, and the actor the table function reads). */
    private static Connection stampedAs(UUID tenant, UUID actor) throws SQLException {
        Connection session = DriverManager.getConnection(POSTGRES.getJdbcUrl(), login.username(), login.password());
        try (PreparedStatement stamp = session.prepareStatement(
                "select set_config('app.tenant_id', ?, false), set_config('app.actor_id', ?, false)")) {
            stamp.setString(1, tenant.toString());
            stamp.setString(2, actor.toString());
            stamp.execute();
        }
        return session;
    }

    private static String callFunction(Connection session, String sql, String... ids) throws SQLException {
        try (PreparedStatement call = session.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                call.setString(i + 1, ids[i]);
            }
            try (ResultSet rs = call.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
