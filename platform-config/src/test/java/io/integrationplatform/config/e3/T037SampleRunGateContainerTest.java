package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.RestrictedLogin;
import io.integrationplatform.config.e3.LaneCLifecycle.Actors;
import io.integrationplatform.e3lanec.E3LaneCSuiteApp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T-037 (E7-C5, backlog.md:1889-1893): "Publish without a sample run is refused" - a mapping that changed since
 * its last passing sample run refuses the publish and asks for the sample run, and a publish whose pinned
 * mapping content exactly matches a passing sample run goes through. The gate sits in the publish pin check's
 * call site ({@code MappingPublishPinCheck}): the gate key is the pinned mapping version's content hash (the
 * same {@code content_sha256} the mapping save mints), the sample run it is compared against is the last
 * PASSING run of exactly that content ({@code V2_2_730}'s {@code mapping_sample_run}).
 *
 * <pre>@negative Scenario: Publish without a sample run is refused
 *   Given a mapping changed since its last sample run
 *   When publish is attempted
 *   Then it is refused and asks for the sample run</pre>
 */
@Tag("container")
@SpringBootTest(classes = E3LaneCSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
// Its own restricted login makes its own context: closed after the class so the shared container keeps its
// connection slots for the suites after it (the parity suites' pattern).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class T037SampleRunGateContainerTest {

    private static final String ROUTES = "/api/v1/admin/routes";

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

    @Test
    @Tag("negative")
    @DisplayName("Publish without a sample run is refused and asks for the sample run (nothing published)")
    void publishWithoutAnySampleRunIsRefused() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        Approved approved = approvedVersion(base, actor);
        UUID route = approved.route;
        UUID otherRoute = approved.otherRoute;

        Response refused = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.FLOWS + "/" + approved.draft.flowId() + "/publish", actor.approver(), "{}");
        assertThat(refused.status()).as("the publish asks for the sample run, body: %s", refused.body())
                .isEqualTo(409);
        assertThat(refused.body()).as("the refusal names the route and the sample run")
                .contains(route.toString())
                .contains("sample run");
        assertThat(LaneCLifecycle.versionStatus(approved.draft.flowId(), 2))
                .as("nothing is published").isEqualTo("APPROVED");
    }

    @Test
    @Tag("negative")
    @DisplayName("A passing sample run of different content does not unlock a changed mapping")
    void changedSinceItsLastPassingSampleRunIsRefused() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        Approved approved = approvedVersion(base, actor);

        seedSampleRun(routeMappingId(approved.route), flipAByte(contentHashOf(approved.route)), true);
        seedSampleRun(routeMappingId(approved.otherRoute), flipAByte(contentHashOf(approved.otherRoute)), true);

        Response refused = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.FLOWS + "/" + approved.draft.flowId() + "/publish", actor.approver(), "{}");
        assertThat(refused.status())
                .as("a mapping whose content differs from its last passing run is refused, body: %s",
                        refused.body()).isEqualTo(409);
        assertThat(refused.body()).as("the refusal names the route and the sample run")
                .contains(approved.route.toString()).contains("sample run");
        assertThat(LaneCLifecycle.versionStatus(approved.draft.flowId(), 2))
                .as("nothing is published").isEqualTo("APPROVED");
    }

    @Test
    @Tag("negative")
    @DisplayName("A sample run that did not pass does not unlock publish")
    void aSampleRunThatDidNotPassDoesNotUnlockPublish() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        Approved approved = approvedVersion(base, actor);
        seedSampleRun(routeMappingId(approved.route), contentHashOf(approved.route), false);
        seedSampleRun(routeMappingId(approved.otherRoute), contentHashOf(approved.otherRoute), false);

        Response refused = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.FLOWS + "/" + approved.draft.flowId() + "/publish", actor.approver(), "{}");
        assertThat(refused.status())
                .as("only a PASSING sample run unlocks publish, body: %s", refused.body()).isEqualTo(409);
        assertThat(refused.body()).as("the refusal asks for the sample run")
                .contains("sample run");
        assertThat(LaneCLifecycle.versionStatus(approved.draft.flowId(), 2))
                .as("nothing is published").isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("A mapping whose pinned content matches its last passing sample run publishes")
    void theLastPassingSampleRunOfThePinnedContentPublishes() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        Approved approved = approvedVersion(base, actor);
        seedSampleRun(routeMappingId(approved.route), contentHashOf(approved.route), true);
        seedSampleRun(routeMappingId(approved.otherRoute), contentHashOf(approved.otherRoute), true);

        Response published = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.FLOWS + "/" + approved.draft.flowId() + "/publish", actor.approver(), "{}");
        assertThat(published.status())
                .as("matching sample runs publish, body: %s", published.body()).isEqualTo(200);
        assertThat(LaneCLifecycle.versionStatus(approved.draft.flowId(), 2)).isEqualTo("PUBLISHED");
    }

    // ------------------------------------------------------------------ helpers

    /** The draft with its two routes bound, re-snapshotted, submitted and approved - publish is next. */
    private Approved approvedVersion(String base, Actors actor) throws SQLException {
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID otherRoute = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 2);
        UUID pinned = sourceVersionOf(draft.sourceSystem(), "1.0");
        bind(route, pinned, targetVersion(systemId(draft.destination1()), "gate-a-" + draft.flowId()));
        bind(otherRoute, pinned, targetVersion(systemId(draft.destination2()), "gate-b-" + draft.flowId()));
        LaneCLifecycle.saveFlowDraft(base, actor.author(), draft.flowId(), draft.sourceSystem(),
                draft.destinations());
        LaneCLifecycle.submit(base, actor, draft.flowId(), "T-037: the sample-run gate");
        Response ok = LaneCLifecycle.approve(base, actor.approver(), draft.flowId());
        assertThat(ok.status()).as("the version is approved, body: %s", ok.body()).isEqualTo(200);
        return new Approved(draft, route, otherRoute);
    }

    private record Approved(LaneCLifecycle.Draft draft, UUID route, UUID otherRoute) {
    }

    /** A draft flow of one source and two destinations whose source contract versions are approved (T-190's). */
    private LaneCLifecycle.Draft draftWithApprovedSource(String base, Actors actor, String... labels)
            throws SQLException {
        String sourceSystem = LaneCIdentities.randomCode("src");
        String destination1 = LaneCIdentities.randomCode("tgt");
        String destination2 = LaneCIdentities.randomCode("tgt");
        LaneCLifecycle.createSystem(base, actor.author(), sourceSystem, "INBOUND");
        LaneCLifecycle.createSystem(base, actor.author(), destination1, "OUTBOUND");
        LaneCLifecycle.createSystem(base, actor.author(), destination2, "OUTBOUND");
        for (String label : labels) {
            sourceVersionOf(sourceSystem, label);
        }
        return new LaneCLifecycle.Draft(
                LaneCLifecycle.createFlow(base, actor.author(), sourceSystem,
                        java.util.List.of(destination1, destination2), "$.orderId"),
                sourceSystem, destination1, destination2);
    }

    private void bind(UUID route, UUID source, UUID target) {
        Response saved = ConfigAdminApi.call("PUT", routesPath(ConfigAdminApi.baseUrl(port), route),
                actors().author(), mappingBody(
                "[{\"source\":\"customerId\",\"target\":\"customer_code\"}]", "$.bound", source, target));
        assertThat(saved.status()).as("the mapping binds one source and one target version, body: %s",
                saved.body()).isEqualTo(200);
    }

    private static String routesPath(String base, UUID routeId) {
        return base + ROUTES + "/" + routeId + "/mapping";
    }

    private static final tools.jackson.databind.json.JsonMapper BODY_JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static String mappingBody(String fieldMappings, String compiledExpression, UUID sourceVersion,
            UUID targetVersion) {
        tools.jackson.databind.node.ObjectNode json = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        json.set("fieldMappings", BODY_JSON.readTree(fieldMappings));
        json.put("compiledExpression", compiledExpression);
        json.put("sourceContractVersionId", sourceVersion.toString());
        json.put("targetContractVersionId", targetVersion.toString());
        return BODY_JSON.writeValueAsString(json);
    }

    private UUID sourceVersionOf(String sourceSystem, String label) throws SQLException {
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
        UUID system = systemId(sourceSystem);
        try (Connection owner = E3ConfigDatabase.ownerConnection()) {
            try (PreparedStatement create = owner.prepareStatement(
                    "insert into public.contract (id, tenant_id, system_id, code, role, name)"
                            + " values (?::uuid, ?::uuid, ?::uuid, ?, 'SOURCE_REQUEST', ?)"
                            + " on conflict (tenant_id, id) do nothing")) {
                create.setString(1, contractId.toString());
                create.setString(2, tenant.toString());
                create.setString(3, system.toString());
                create.setString(4, sourceSystem);
                create.setString(5, "T-037 source contract of " + sourceSystem);
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

    private UUID targetVersion(UUID systemId, String name) throws SQLException {
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
                contract.setString(2, "E7T-" + name);
                contract.setString(3, "E7T-" + name);
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
                        + "\"customer_code\":{\"type\":\"string\"},"
                        + "\"order_number\":{\"type\":\"string\"},"
                        + "\"total_amount\":{\"type\":\"number\"}}}");
                version.setString(4, UUID.randomUUID().toString());
                version.setString(5, UUID.randomUUID().toString());
                version.setString(6, systemId.toString());
                version.executeUpdate();
            }
            return id;
        }
    }

    private UUID systemId(String code) throws SQLException {
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

    private UUID routeMappingId(UUID routeId) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select id::text from public.mapping where route_id = ?::uuid")) {
            read.setString(1, routeId.toString());
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).as("the route's mapping row exists").isTrue();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    /** The content hash of the route mapping's latest (pinned) mapping_version row. */
    private byte[] contentHashOf(UUID routeId) throws SQLException {
        byte[] hash = contentHashOrNull(routeId);
        assertThat(hash).as("the pinned mapping version exists and carries its content hash").isNotNull();
        return hash;
    }

    private byte[] contentHashOrNull(UUID routeId) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection(); PreparedStatement read = owner
                .prepareStatement("select v.content_sha256 from public.mapping_version v"
                        + " join public.mapping m on m.id = v.mapping_id"
                        + " where m.route_id = ?::uuid order by v.version desc limit 1")) {
            read.setString(1, routeId.toString());
            try (ResultSet rs = read.executeQuery()) {
                if (rs.next()) {
                    return rs.getBytes(1);
                }
                return null;
            }
        }
    }

    private static byte[] flipAByte(byte[] hash) {
        byte[] flipped = hash.clone();
        flipped[0] ^= 0x01;
        return flipped;
    }

    /** A sample run for the mapping, as the test endpoint's recorder leaves it (seeded as the owner). */
    private void seedSampleRun(UUID mappingId, byte[] contentSha256, boolean passed) throws SQLException {
        byte[] sampleSha256 = sha256(UUID.randomUUID() + "|" + mappingId);
        UUID actor = UUID.randomUUID();
        try (Connection owner = E3ConfigDatabase.ownerConnection(); PreparedStatement insert = owner
                .prepareStatement("insert into public.mapping_sample_run"
                        + " (tenant_id, mapping_id, content_sha256, sample_sha256, passed, actor_id)"
                        + " values (?::uuid, ?::uuid, ?, ?, ?, ?::uuid)")) {
            insert.setString(1, E3ConfigDatabase.tenantA().id().toString());
            insert.setString(2, mappingId.toString());
            insert.setBytes(3, contentSha256);
            insert.setBytes(4, sampleSha256);
            insert.setBoolean(5, passed);
            insert.setString(6, actor.toString());
            assertThat(insert.executeUpdate()).as("the sample run seeds").isEqualTo(1);
        }
    }

    /** The SHA-256 of the message, for the seeded sample hashes. */
    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("no SHA-256", e);
        }
    }
}