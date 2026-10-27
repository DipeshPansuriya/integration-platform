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
import io.integrationplatform.config.credential.application.CredentialAudit;
import io.integrationplatform.config.rule.application.LookupEntryService;
import io.integrationplatform.config.rule.application.RuleSetAdminService;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import io.integrationplatform.e5.contract.E5ContractFixture.Tenant;
import io.integrationplatform.tenancy.internal.principal.TenantBoundAuthentication;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * E12-F2-S1 (row 327 T117: T-068 remaining hooks + T-134) on the real application and the append-only
 * {@code audit_event} ledger the T109 writer appends to after commit:
 *
 * <pre>
 * Scenario Outline: One event per action
 *   Given a user performing &lt;action&gt;
 *   When it completes
 *   Then exactly one audit event (who, what, when, tenant, target ids, before/after where relevant) is written
 *   Examples: approving a contract version | changing a configuration or lookup entry |
 *             issuing, rotating or revoking a credential
 *
 * Scenario: Events published earlier are persisted
 *   Given audit events published by E5 (test-connection, credentials) and E7 (lookup edits) before the writer existed
 *   When the E5 and E7 audit tests re-run against the audit table
 *   Then each finds its row
 * </pre>
 *
 * <p>The contract walk goes through the real HTTP identity chain (the E5 lane-A fixture this class sits beside). The
 * credential and lookup paths run on the running context's own beans with the tenant bound the way a request binds
 * it ({@link TenantBoundAuthentication} on the security context, the E5 zero-deploy shape): the credential audit
 * opens its own write transaction - the credential services are not transactional (Keycloak admin calls) - and the
 * row must still land. The E5 test-connection audit is fenced out of row 327 (its service is wired in a file row 278
 * owns) and stays a log line, so it is not re-run here. The ledger's immutability is T109's
 * {@code AuditWriterContainerTest}, not repeated. Rows are read as the owner, per tenant: every test seeds its own.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "platform.config.destinations.allowed-hosts=*.partner.example")
@Import(E12ConfigAuditTrailContainerTest.SuiteDecoder.class)
class E12ConfigAuditTrailContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LOOKUP_TABLE = "{\"nodes\":["
            + "{\"id\":\"request\",\"type\":\"inputNode\",\"name\":\"Request\",\"position\":{\"x\":0,\"y\":0}},"
            + "{\"id\":\"table\",\"type\":\"decisionTableNode\",\"name\":\"Checks\",\"position\":{\"x\":240,\"y\":0},"
            + "\"content\":{\"hitPolicy\":\"first\",\"inputs\":[{\"id\":\"c1\",\"name\":\"Code\",\"field\":\"code\"}],"
            + "\"outputs\":[{\"id\":\"o1\",\"name\":\"Name\",\"field\":\"name\"}],"
            + "\"rules\":[{\"_id\":\"r1\",\"c1\":\"\\\"NL\\\"\",\"o1\":\"\\\"Netherlands\\\"\"},"
            + "{\"_id\":\"r2\",\"c1\":\"\\\"DE\\\"\",\"o1\":\"\\\"Germany\\\"\"}]}},"
            + "{\"id\":\"response\",\"type\":\"outputNode\",\"name\":\"Response\",\"position\":{\"x\":480,\"y\":0}}],"
            + "\"edges\":[{\"id\":\"e1\",\"sourceId\":\"request\",\"targetId\":\"table\",\"type\":\"edge\"},"
            + "{\"id\":\"e2\",\"sourceId\":\"table\",\"targetId\":\"response\",\"type\":\"edge\"}]}";

    private static RestrictedLogin login;

    @LocalServerPort
    private int port;

    @Autowired
    private CredentialAudit credentialAudit;

    @Autowired
    private RuleSetAdminService ruleSets;

    @Autowired
    private LookupEntryService lookups;

    @Autowired
    private PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SuiteDecoder {

        @Bean
        @Primary
        JwtDecoder e12ConfigAuditSuiteJwtDecoder() {
            return E5ContractFixture.subjectDecoder();
        }
    }

    // ------------------------------------------------------------------ One event per action: contract approval

    @Test
    @DisplayName("One event per action: a contract import and its approval each land exactly one audit_event row;"
            + " a configurator's refused approval lands none")
    void aContractImportAndApprovalEachLandOneRow() throws Exception {
        Tenant tenant = seed("ct");
        String author = tenant.subject("CONFIGURATOR");
        String target = registerTargetSystem(port, tenant, "tgt-audit");

        String versionId = importVersion(port, author, target, "1.0", "JSON_SCHEMA", TARGET_A_V1);
        assertThat(rows(tenant, ConfigAuditActions.CONTRACT_VERSION_IMPORTED))
                .as("one import, one row: actor, target ids, after state")
                .containsExactly(tenant.userId("CONFIGURATOR") + "|contract_version:" + versionId
                        + "|{\"status\":\"DRAFT\",\"versionLabel\":\"1.0\"}");
        assertThat(rowText(tenant, ConfigAuditActions.CONTRACT_VERSION_IMPORTED)).as("never the schema")
                .doesNotContain("customer_code");

        submit(port, author, versionId);
        assertThat(rows(tenant, ConfigAuditActions.CONTRACT_SUBMITTED)).as("one submit, one row")
                .containsExactly(tenant.userId("CONFIGURATOR") + "|contract_version:" + versionId
                        + "|{\"status\":\"PENDING_APPROVAL\"}");
        HttpResponse<String> refused = approve(port, author, versionId);
        assertThat(refused.statusCode()).as("a configurator holds no approve role: %s", refused.body()).isEqualTo(403);
        assertThat(count(tenant, ConfigAuditActions.CONTRACT_VERSION_APPROVED))
                .as("a refused approval rolls back and writes no audit row").isZero();

        HttpResponse<String> approved = approve(port, tenant.subject("APPROVER"), versionId);
        assertThat(approved.statusCode()).as("approve: %s", approved.body()).isEqualTo(200);
        assertThat(rows(tenant, ConfigAuditActions.CONTRACT_VERSION_APPROVED))
                .as("one approval, one row naming the approver")
                .containsExactly(tenant.userId("APPROVER") + "|contract_version:" + versionId
                        + "|{\"status\":\"APPROVED\",\"versionLabel\":\"1.0\"}");
    }

    // ------------------------------------------------------------------ Events published earlier are persisted

    @Test
    @DisplayName("Events published earlier are persisted: the E5 credential audit path lands one row per action,"
            + " published in its own write transaction (the services are not transactional)")
    void theE5CredentialAuditPathLandsItsRows() throws Exception {
        Tenant tenant = seed("cr");
        UUID actor = tenant.userId("TENANT_ADMIN");
        UUID system = UUID.randomUUID();
        UUID credential = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        UUID endpoint = UUID.randomUUID();

        asTenantUser(tenant, "TENANT_ADMIN", () -> {
            credentialAudit.sourceIssued(tenant.id(), actor, system, credential, "client-must-not-land");
            credentialAudit.sourceRotated(tenant.id(), actor, credential, replacement);
            credentialAudit.sourceRevoked(tenant.id(), actor, credential, "REVOKED");
            // the target save publishes inside its own open transaction: the audit joins it, one row after commit
            new TransactionTemplate(transactions).executeWithoutResult(status -> credentialAudit.targetSaved(
                    tenant.id(), actor, endpoint, List.of(credential), List.of("password")));
            return null;
        });

        assertThat(count(tenant, ConfigAuditActions.CREDENTIAL_SOURCE_ISSUED)).isEqualTo(1);
        assertThat(count(tenant, ConfigAuditActions.CREDENTIAL_SOURCE_ROTATED)).isEqualTo(1);
        assertThat(count(tenant, ConfigAuditActions.CREDENTIAL_SOURCE_REVOKED)).isEqualTo(1);
        assertThat(rows(tenant, ConfigAuditActions.CREDENTIAL_TARGET_SAVED))
                .containsExactly(actor + "|endpoint:" + endpoint + ",credential:" + credential
                        + "|{\"fields\":[\"password\"]}");
        assertThat(scalar(POSTGRES, "SELECT count(*)::text FROM audit_event WHERE tenant_id = ?::uuid"
                + " AND (target_ids::text LIKE '%client-must-not-land%' OR coalesce(after_state, '')"
                + " LIKE '%client-must-not-land%')", tenant.id().toString()))
                .as("no client id, no secret in the ledger").isEqualTo("0");
    }

    @Test
    @DisplayName("Events published earlier are persisted: the E7 lookup edit lands its row; the rule-set create and"
            + " update land theirs (configuration change)")
    void theE7LookupEditAndRuleSetEditsLandTheirRows() throws Exception {
        Tenant tenant = seed("lk");
        UUID flowId = UUID.randomUUID();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            E5ContractFixture.execute(owner, "INSERT INTO public.interface (id, tenant_id, code, name)"
                    + " VALUES (?::uuid, ?::uuid, ?, ?)", flowId.toString(), tenant.id().toString(),
                    "flow-audit-" + E5ContractFixture.shortId(), "audited flow");
        }
        LoginBinding configurator = binding(tenant, "CONFIGURATOR");

        String lookupId = asTenantUser(tenant, "CONFIGURATOR", () -> ruleSets.create(flowId.toString(),
                JSON.readTree("{\"kind\":\"LOOKUP\",\"name\":\"country codes\",\"content\":" + LOOKUP_TABLE + "}"),
                configurator.userId()).id());
        asTenantUser(tenant, "CONFIGURATOR", () -> ruleSets.update(lookupId,
                JSON.readTree("{\"name\":\"country codes v2\",\"content\":" + LOOKUP_TABLE + "}"),
                configurator.userId()));
        asTenantUser(tenant, "CONFIGURATOR", () -> lookups.change(lookupId, "r1",
                JSON.readTree("{\"key\":\"NL\",\"value\":\"Kingdom of the Netherlands\"}"), configurator));

        String who = configurator.userId().toString();
        assertThat(rows(tenant, ConfigAuditActions.RULE_SET_CREATED))
                .containsExactly(who + "|interface:" + flowId + ",rule_set:" + lookupId
                        + "|{\"kind\":\"LOOKUP\",\"name\":\"country codes\"}");
        assertThat(rows(tenant, ConfigAuditActions.RULE_SET_UPDATED))
                .containsExactly(who + "|interface:" + flowId + ",rule_set:" + lookupId
                        + "|{\"kind\":\"LOOKUP\",\"name\":\"country codes v2\"}");
        assertThat(rows(tenant, LookupEntryService.ACTION)).as("the E7 lookup event, unchanged, now has its row")
                .containsExactly(who + "|" + lookupId + ",r1"
                        + "|{\"key\":\"NL\",\"value\":\"Kingdom of the Netherlands\"}");
    }

    // ------------------------------------------------------------------ A destination change reaches the ledger

    @Test
    @DisplayName("Review A-C2: an API system create and a base-URL change through the admin API each land one"
            + " SYSTEM_DESTINATION_CHANGED row - actor, system id, old and new HOST only, never path, query or scheme")
    void aDestinationChangeLandsOneHostOnlyRowInTheLedger() throws Exception {
        Tenant tenant = seed("dest");
        String admin = tenant.subject("TENANT_ADMIN");
        String actor = tenant.userId("TENANT_ADMIN").toString();
        String code = "sys-dest-" + shortId();

        HttpResponse<String> created = call(port, admin, "POST", "/api/v1/admin/systems", systemJson(code,
                "https://one.partner.example/v1/orders?token=first-secret-query"));
        assertThat(created.statusCode()).as("create: %s", created.body()).isEqualTo(201);
        String system = E5ContractFixture.json(created).path("id").asString();
        assertThat(destinationRows(tenant)).as("create: one row, no before, the new host")
                .containsExactly(actor + "|system:" + system + "||{\"host\":\"one.partner.example\"}");

        HttpResponse<String> changed = call(port, admin, "PUT", "/api/v1/admin/systems/" + system, systemJson(code,
                "https://two.partner.example/v2/orders?token=second-secret-query"));
        assertThat(changed.statusCode()).as("change: %s", changed.body()).isEqualTo(200);
        assertThat(destinationRows(tenant)).as("a changed address adds exactly one more row, old and new host")
                .containsExactly(actor + "|system:" + system + "||{\"host\":\"one.partner.example\"}",
                        actor + "|system:" + system + "|{\"host\":\"one.partner.example\"}|{\"host\":\"two.partner.example\"}");

        HttpResponse<String> same = call(port, admin, "PUT", "/api/v1/admin/systems/" + system, systemJson(code,
                "https://two.partner.example/v2/orders?token=second-secret-query"));
        assertThat(same.statusCode()).isEqualTo(200);
        HttpResponse<String> refused = call(port, admin, "PUT", "/api/v1/admin/systems/" + system, systemJson(code,
                "https://evil.example/steal"));
        assertThat(refused.statusCode()).as("off-list host: %s", refused.body()).isEqualTo(400);
        assertThat(count(tenant, ConfigAuditActions.SYSTEM_DESTINATION_CHANGED))
                .as("an unchanged address and a refused one write no row").isEqualTo(2);

        assertThat(rowText(tenant, ConfigAuditActions.SYSTEM_DESTINATION_CHANGED))
                .as("hosts only: no scheme, path, query or userinfo in the ledger")
                .doesNotContain("https", "/v1", "/v2", "orders", "token", "secret", "?", "@");
    }

    @Test
    @DisplayName("Row 510/519: an endpoint-auth change lands one SYSTEM_ENDPOINT_AUTH_CHANGED row - actor, system id,"
            + " method, identity, token HOST only; a configurator's refused PUT and a refused token URL land none")
    void anEndpointAuthChangeLandsOneHostOnlyRowInTheLedger() throws Exception {
        Tenant tenant = seed("eauth");
        String admin = tenant.subject("TENANT_ADMIN");
        String actor = tenant.userId("TENANT_ADMIN").toString();
        String system = createApiSystem(admin, "sys-eauth-" + shortId());
        String path = "/api/v1/admin/systems/" + system + "/endpoint-auth";

        HttpResponse<String> forbidden = call(port, tenant.subject("CONFIGURATOR"), "PUT", path,
                "{\"authMethod\":\"NO_AUTH\"}");
        assertThat(forbidden.statusCode()).as("configurator: %s", forbidden.body()).isEqualTo(403);
        assertThat(count(tenant, ConfigAuditActions.SYSTEM_ENDPOINT_AUTH_CHANGED)).as("a 403 writes no row").isZero();

        HttpResponse<String> changed = call(port, admin, "PUT", path, "{\"authMethod\":\"OAUTH2_CLIENT_CREDENTIALS\","
                + "\"authIdentity\":\"client-1\",\"authTokenUrl\":\"https://auth.partner.example/oauth/token?k=tok-secret\"}");
        assertThat(changed.statusCode()).as("admin: %s", changed.body()).isEqualTo(200);
        assertThat(rows(tenant, ConfigAuditActions.SYSTEM_ENDPOINT_AUTH_CHANGED)).singleElement().asString()
                .startsWith(actor + "|system:" + system + "|")
                .contains("OAUTH2_CLIENT_CREDENTIALS", "client-1", "auth.partner.example");

        HttpResponse<String> refused = call(port, admin, "PUT", path, "{\"authMethod\":\"OAUTH2_CLIENT_CREDENTIALS\","
                + "\"authIdentity\":\"client-1\",\"authTokenUrl\":\"https://evil.example/token\"}");
        assertThat(refused.statusCode()).as("off-list token URL: %s", refused.body()).isEqualTo(400);
        assertThat(count(tenant, ConfigAuditActions.SYSTEM_ENDPOINT_AUTH_CHANGED))
                .as("a refused save writes no row").isEqualTo(1);
        assertThat(rowText(tenant, ConfigAuditActions.SYSTEM_ENDPOINT_AUTH_CHANGED))
                .as("host only: no scheme, path, query in the ledger")
                .doesNotContain("https", "/oauth", "tok-secret", "?");
    }

    @Test
    @DisplayName("Row 510/519: an OAuth2 token URL aimed at a loopback sink is refused at save (400), the stored URL is"
            + " untouched and the sink's journal shows 0 requests")
    void aRefusedOAuth2TokenUrlNeverReachesTheSink() throws Exception {
        java.util.concurrent.atomic.AtomicInteger journal = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer sink = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        sink.createContext("/", exchange -> {
            journal.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        sink.start();
        try {
            Tenant tenant = seed("eauthsink");
            String admin = tenant.subject("TENANT_ADMIN");
            String system = createApiSystem(admin, "sys-sink-" + shortId());
            String path = "/api/v1/admin/systems/" + system + "/endpoint-auth";
            String good = "https://auth.partner.example/oauth/token";
            HttpResponse<String> saved = call(port, admin, "PUT", path, oauth2Body(good));
            assertThat(saved.statusCode()).as("positive control: %s", saved.body()).isEqualTo(200);

            int sinkPort = sink.getAddress().getPort();
            for (String hostile : List.of("http://localhost:" + sinkPort + "/token",
                    "http://127.0.0.1:" + sinkPort + "/token", "https://localhost:" + sinkPort + "/token",
                    "https://127.0.0.1:" + sinkPort + "/token", "https://[::1]:" + sinkPort + "/token",
                    "https://evil.example@127.0.0.1:" + sinkPort + "/token")) {
                HttpResponse<String> response = call(port, admin, "PUT", path, oauth2Body(hostile));
                assertThat(response.statusCode()).as("%s -> %s", hostile, response.body()).isEqualTo(400);
            }
            HttpResponse<String> after = call(port, admin, "GET", path, null);
            assertThat(E5ContractFixture.json(after).path("authTokenUrl").asString()).as("stored URL untouched")
                    .isEqualTo(good);
            assertThat(journal.get()).as("sink journal").isZero();
        } finally {
            sink.stop(0);
        }
    }

    private String createApiSystem(String adminSubject, String code) throws Exception {
        HttpResponse<String> created = call(port, adminSubject, "POST", "/api/v1/admin/systems",
                systemJson(code, "https://one.partner.example/v1/orders"));
        assertThat(created.statusCode()).as("create: %s", created.body()).isEqualTo(201);
        return E5ContractFixture.json(created).path("id").asString();
    }

    private static String oauth2Body(String tokenUrl) {
        return "{\"authMethod\":\"OAUTH2_CLIENT_CREDENTIALS\",\"authIdentity\":\"client-1\",\"authTokenUrl\":\""
                + tokenUrl + "\"}";
    }

    private static String systemJson(String code, String baseUrl) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"direction\":\"OUTBOUND\",\"type\":\"API\","
                + "\"settings\":{\"baseUrl\":\"" + baseUrl + "\",\"timeoutMs\":3000}}";
    }

    /** Each destination row as {@code actor|target ids|before|after}, oldest first. */
    private static List<String> destinationRows(Tenant tenant) throws Exception {
        String joined = scalar(POSTGRES, "SELECT coalesce(string_agg(actor || '|'"
                + " || (SELECT string_agg(t, ',' ORDER BY n) FROM jsonb_array_elements_text(target_ids)"
                + " WITH ORDINALITY AS x(t, n)) || '|' || coalesce(before_state, '') || '|' || coalesce(after_state, ''),"
                + " E'\n' ORDER BY id), '') FROM audit_event WHERE tenant_id = ?::uuid AND action = ?",
                tenant.id().toString(), ConfigAuditActions.SYSTEM_DESTINATION_CHANGED);
        return joined.isEmpty() ? List.of() : List.of(joined.split("\\n"));
    }

    // ------------------------------------------------------------------ helpers

    private Tenant seed(String word) throws Exception {
        Tenant tenant = E5ContractFixture.seedTenant(POSTGRES, word, "TENANT_ADMIN", "CONFIGURATOR", "APPROVER");
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        return tenant;
    }

    private static LoginBinding binding(Tenant tenant, String role) {
        return new LoginBinding(tenant.userId(role), "ACTIVE", tenant.id(), tenant.code(), "ACTIVE",
                ZoneId.of("Etc/UTC"));
    }

    /** Runs {@code work} with the tenant user bound the way a request binds it; the binding is cleared after. */
    private static <T> T asTenantUser(Tenant tenant, String role, Supplier<T> work) {
        SecurityContext security = SecurityContextHolder.createEmptyContext();
        Jwt jwt = Jwt.withTokenValue("e12-config-audit").header("alg", "none").subject(tenant.subject(role)).build();
        security.setAuthentication(new TenantBoundAuthentication(new JwtAuthenticationToken(jwt),
                binding(tenant, role)));
        SecurityContextHolder.setContext(security);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static int count(Tenant tenant, String action) throws Exception {
        return Integer.parseInt(scalar(POSTGRES, "SELECT count(*)::text FROM audit_event"
                + " WHERE tenant_id = ?::uuid AND action = ?", tenant.id().toString(), action));
    }

    /** Each row of the action as {@code actor|target,ids|after_state}, oldest first. */
    private static List<String> rows(Tenant tenant, String action) throws Exception {
        String joined = scalar(POSTGRES, "SELECT coalesce(string_agg(actor || '|'"
                + " || (SELECT string_agg(t, ',' ORDER BY n) FROM jsonb_array_elements_text(target_ids)"
                + " WITH ORDINALITY AS x(t, n)) || '|' || coalesce(after_state, ''), E'\\n' ORDER BY id), '')"
                + " FROM audit_event WHERE tenant_id = ?::uuid AND action = ?", tenant.id().toString(), action);
        return joined.isEmpty() ? List.of() : List.of(joined.split("\n"));
    }

    private static String rowText(Tenant tenant, String action) throws Exception {
        return scalar(POSTGRES, "SELECT coalesce(string_agg(target_ids::text || coalesce(before_state, '')"
                + " || coalesce(after_state, ''), ''), '') FROM audit_event WHERE tenant_id = ?::uuid"
                + " AND action = ?", tenant.id().toString(), action);
    }
}
