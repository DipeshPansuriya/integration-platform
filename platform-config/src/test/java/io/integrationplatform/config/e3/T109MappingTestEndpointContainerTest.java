package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.RestrictedLogin;
import io.integrationplatform.config.e3.LaneCLifecycle.Actors;
import io.integrationplatform.e3lanec.E3LaneCSuiteApp;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E7 lane B2 (T-108/T-109): the mapping test endpoint {@code POST /api/v1/admin/mappings/{id}/test} - the stored
 * compiled expression runs on the posted sample through the real transform (Dashjoin JSONata, the 5 s budget of
 * backlog.md:1896-1899), the round trip answers the transform's status and output, an unknown or foreign mapping
 * answers 404 and a post without a sample answers 400. The sample run itself is recorded through the
 * {@code MappingSampleRuns} seam (wave 1: the no-op bean; lane C2's JDBC bean makes the record durable - its
 * publish gate is the reader). The endpoint is EDIT_CONFIGURATION (MappingTestEndpointRoles).
 */
@Tag("container")
@SpringBootTest(classes = E3LaneCSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class T109MappingTestEndpointContainerTest {

    private static final String ROUTES = "/api/v1/admin/routes";
    private static final String MAPPINGS = "/api/v1/admin/mappings";

    private static final AtomicBoolean SEEDED = new AtomicBoolean();
    private static final LaneCIdentities TOKENS = E3LaneCSuiteApp.TOKENS;

    private static RestrictedLogin login;
    private static String approverSubject;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        PostgreSQLContainer postgres = E3ConfigDatabase.postgres();
        if (SEEDED.compareAndSet(false, true)) {
            E3ConfigDatabase.seedDirectory();
            approverSubject = LaneCIdentities.seedApproverOfTenantA();
        }
        login = E3ConfigDatabase.restrictedLogin();
        LaneCIdentities.assertPremise(login, approverSubject);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> login.username());
        registry.add("spring.datasource.password", () -> login.password());
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> E3ConfigDatabase.OIDC_ISSUER.toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences",
                () -> E3ConfigDatabase.OIDC_AUDIENCE);
        registry.add("spring.security.oauth2.resourceserver.jwt.jws-algorithms", () -> "RS256");
    }

    private Actors actors() {
        return Actors.of(TOKENS, E3ConfigDatabase.tenantA().subject(), approverSubject,
                E3ConfigDatabase.tenantB().subject());
    }

    // ------------------------------------------------------------------ the round trip

    @Test
    @DisplayName("Scenario: the stored mapping runs on a sample and answers the transform's output")
    void theStoredMappingRunsOnASample() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID mappingId = saveMapping(base, actor, route, draft,
                "[{\"source\":\"customerId\",\"target\":\"customer_code\"}]",
                "{\"customer_code\": customerId}");
        String bearer = actor.author();

        Response answered = ConfigAdminApi.call("POST", base + MAPPINGS + "/" + mappingId + "/test", bearer,
                "{\"sample\":{\"customerId\":\"acme-1\"}}");

        assertThat(answered.status()).as("the sample runs, body: %s", answered.body()).isEqualTo(200);
        assertThat(answered.json().path("mappingId").asString())
                .as("the answer names the tested mapping").isEqualTo(mappingId.toString());
        assertThat(answered.json().path("status").asString()).as("the transform answers OK").isEqualTo("OK");
        assertThat(answered.json().path("output").toString())
                .as("the output is mapped through the stored expression").contains("customer_code")
                .contains("acme-1");
        assertThat(answered.json().path("error").isNull() || answered.json().path("error").isMissingNode())
                .as("a pass carries no error").isTrue();
    }

    @Test
    @DisplayName("Scenario: a broken expression answers ERROR with the engine's reason")
    void aBrokenExpressionAnswersError() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID mappingId = saveMapping(base, actor, route, draft, "[]", "amount *");
        String bearer = actor.author();

        Response answered = ConfigAdminApi.call("POST", base + MAPPINGS + "/" + mappingId + "/test", bearer,
                "{\"sample\":{\"customerId\":\"acme-1\",\"amount\":\"no-number\"}}");

        assertThat(answered.status()).as("the broken expression still answers, body: %s", answered.body())
                .isEqualTo(200);
        assertThat(answered.json().path("status").asString()).as("the transform answers ERROR").isEqualTo("ERROR");
        assertThat(answered.json().path("error").asString() == null
                || !answered.json().path("error").asString().isBlank())
                .as("the engine's reason is carried").isTrue();
    }

    @Test
    @DisplayName("An unknown mapping answers 404")
    void anUnknownMappingAnswers404() {
        String base = ConfigAdminApi.baseUrl(port);
        String bearer = actors().author();

        Response answered = ConfigAdminApi.call("POST", base + MAPPINGS + "/" + UUID.randomUUID() + "/test",
                bearer, "{}");

        assertThat(answered.status()).as("an unknown mapping answers 404, body: %s", answered.body())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("A foreign tenant's mapping answers 404, never 403")
    void aForeignMappingAnswers404() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID mappingId = saveMapping(base, actor, route, draft, "[]", "$.bound");

        Response answered = ConfigAdminApi.call("POST", base + MAPPINGS + "/" + mappingId + "/test",
                actor.otherTenantUser(), "{\"sample\":{}}");

        assertThat(answered.status()).as("another tenant's mapping answers 404, body: %s", answered.body())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("A post without a sample answers 400")
    void aMissingSampleAnswers400() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID mappingId = saveMapping(base, actor, route, draft, "[]", "$.bound");
        String bearer = actor.author();

        Response answered = ConfigAdminApi.call("POST", base + MAPPINGS + "/" + mappingId + "/test", bearer,
                "{\"other\":1}");

        assertThat(answered.status()).as("the missing sample is refused, body: %s", answered.body())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------ helpers (the T-190 scaffolding)

    /**
     * A draft flow of one source and two destinations whose source contract version is already approved:
     * the systems are created, the labels are seeded APPROVED, then the flow is created.
     */
    private LaneCLifecycle.Draft draftWithApprovedSource(String base, Actors actor, String... labels)
            throws SQLException {
        String sourceSystem = LaneCIdentities.randomCode("src");
        String destination1 = LaneCIdentities.randomCode("tgt");
        String destination2 = LaneCIdentities.randomCode("tgt");
        LaneCLifecycle.createSystem(base, actor.author(), sourceSystem, "INBOUND");
        LaneCLifecycle.createSystem(base, actor.author(), destination1, "OUTBOUND");
        LaneCLifecycle.createSystem(base, actor.author(), destination2, "OUTBOUND");
        for (String label : labels) {
            seedSourceVersion(sourceSystem, label);
        }
        return new LaneCLifecycle.Draft(
                LaneCLifecycle.createFlow(base, actor.author(), sourceSystem,
                        List.of(destination1, destination2), "$.orderId"),
                sourceSystem, destination1, destination2);
    }

    /** Saves the named mapping for the route and returns the live mapping's id. */
    private UUID saveMapping(String base, Actors actor, UUID route, LaneCLifecycle.Draft draft,
            String fieldMappings, String compiledExpression) throws SQLException {
        UUID source = seedSourceVersion(draft.sourceSystem(), "1.0");
        UUID target = seedTargetVersion(systemOfCode(draft.destination1()), "b2test-" + draft.flowId());
        Response saved = ConfigAdminApi.call("PUT",
                base + ROUTES + "/" + route + "/mapping", actor.author(), mappingBody(
                        fieldMappings, compiledExpression, source, target));
        assertThat(saved.status()).as("the mapping saves, body: %s", saved.body()).isEqualTo(200);
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select id::text from public.mapping where route_id = ?::uuid")) {
            read.setString(1, route.toString());
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).as("the route's mapping row exists").isTrue();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    private static final tools.jackson.databind.json.JsonMapper BODY_JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** Serialized by Jackson, so a compiled expression containing quotes or breaks stays one JSON string. */
    private static String mappingBody(String fieldMappings, String compiledExpression, UUID sourceVersion,
            UUID targetVersion) {
        tools.jackson.databind.node.ObjectNode json = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        json.set("fieldMappings", BODY_JSON.readTree(fieldMappings));
        json.put("compiledExpression", compiledExpression);
        json.put("sourceContractVersionId", sourceVersion.toString());
        json.put("targetContractVersionId", targetVersion.toString());
        return BODY_JSON.writeValueAsString(json);
    }

    /**
     * The APPROVED {@code SOURCE_REQUEST} contract version {@code label} of the named source system, seeded
     * when missing (deterministic id; reading back own seed is the second call's answer).
     */
    private UUID seedSourceVersion(String sourceSystem, String label) throws SQLException {
        UUID id = UUID.nameUUIDFromBytes(("mapping-source|" + sourceSystem + "|" + label).getBytes(
                StandardCharsets.UTF_8));
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select id::text from public.contract_version where id = ?::uuid")) {
            read.setString(1, id.toString());
            try (ResultSet rs = read.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString(1));
                }
            }
        }
        UUID contractId = UUID.nameUUIDFromBytes(("mapping-source-contract|" + sourceSystem).getBytes(
                StandardCharsets.UTF_8));
        UUID tenant = E3ConfigDatabase.tenantA().id();
        UUID system = systemOfCode(sourceSystem);
        try (Connection owner = E3ConfigDatabase.ownerConnection()) {
            try (PreparedStatement create = owner.prepareStatement(
                    "insert into public.contract (id, tenant_id, system_id, code, role, name)"
                            + " values (?::uuid, ?::uuid, ?::uuid, ?, 'SOURCE_REQUEST', ?)"
                            + " on conflict (tenant_id, id) do nothing")) {
                create.setString(1, contractId.toString());
                create.setString(2, tenant.toString());
                create.setString(3, system.toString());
                create.setString(4, sourceSystem);
                create.setString(5, "T-109 source contract of " + sourceSystem);
                create.executeUpdate();
            }
            try (PreparedStatement insert = owner.prepareStatement(
                    "insert into public.contract_version (id, tenant_id, contract_id, version_label, status,"
                            + " json_schema_draft_2020_12, author_id, approver_id)"
                            + " values (?::uuid, ?::uuid, ?::uuid, ?, 'APPROVED', '{\"type\":\"object\"}'::jsonb,"
                            + " ?::uuid, ?::uuid)")) {
                insert.setString(1, id.toString());
                insert.setString(2, tenant.toString());
                insert.setString(3, contractId.toString());
                insert.setString(4, label);
                insert.setString(5, UUID.randomUUID().toString());
                insert.setString(6, UUID.randomUUID().toString());
                assertThat(insert.executeUpdate()).as("the source version %s inserts", label).isEqualTo(1);
            }
        }
        return id;
    }

    /**
     * Seeds (once, by stable name) an APPROVED TARGET_REQUEST version 1.0 of the given system and returns its
     * id, owner-seeded the way the T-190 suite seeds contracts.
     */
    private UUID seedTargetVersion(UUID systemId, String name) throws SQLException {
        UUID id = UUID.nameUUIDFromBytes(("mapping-target|" + name).getBytes());
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select id::text from public.contract_version where id = ?::uuid")) {
            read.setString(1, id.toString());
            try (ResultSet rs = read.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString(1));
                }
            }
            UUID contractId = UUID.nameUUIDFromBytes(("mapping-target-contract|" + name).getBytes());
            try (PreparedStatement contract = owner.prepareStatement(
                    "insert into public.contract (id, tenant_id, system_id, code, role, name)"
                            + " select ?::uuid, s.tenant_id, s.id, ?, 'TARGET_REQUEST', ?"
                            + " from public.system s where s.id = ?::uuid"
                            + " on conflict (tenant_id, code) do nothing")) {
                contract.setString(1, contractId.toString());
                contract.setString(2, "E7B-" + name);
                contract.setString(3, "E7B-" + name);
                contract.setString(4, systemId.toString());
                contract.executeUpdate();
            }
            try (PreparedStatement version = owner.prepareStatement(
                    "insert into public.contract_version (id, tenant_id, contract_id, version_label, status,"
                            + " json_schema_draft_2020_12, author_id, approver_id)"
                            + " select ?::uuid, s.tenant_id, ?::uuid, '1.0', 'APPROVED', ?::jsonb, ?::uuid, ?::uuid"
                            + " from public.system s where s.id = ?::uuid")) {
                version.setString(1, id.toString());
                version.setString(2, contractId.toString());
                version.setString(3, "{\"type\":\"object\",\"properties\":{"
                        + "\"customer_code\":{\"type\":\"string\"}}}");
                version.setString(4, UUID.randomUUID().toString());
                version.setString(5, UUID.randomUUID().toString());
                version.setString(6, systemId.toString());
                version.executeUpdate();
            }
            return id;
        }
    }

    private UUID systemOfCode(String code) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select id::text from public.system where code = ? order by id limit 1")) {
            read.setString(1, code);
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).as("the system %s exists", code).isTrue();
                return UUID.fromString(rs.getString(1));
            }
        }
    }
}