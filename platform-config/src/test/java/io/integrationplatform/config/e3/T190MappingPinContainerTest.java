package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.RestrictedLogin;
import io.integrationplatform.config.e3.LaneCLifecycle.Actors;
import io.integrationplatform.e3lanec.E3LaneCSuiteApp;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.JsonNode;

/**
 * T-190 (lane C1, E7-F2-S5) + the mapping save/read API (E7-F2-S1, backlog.md:1824-1844): the mapping is stored
 * for its one route, bounded by one source and one target contract version (ADR-013), a link to a field the bound
 * target contract does not have is refused, and publish refuses a mapping whose pins differ from the interface
 * version's pins (ADR-011 Decision 3 rule 6) - nothing published, the route and both versions named.
 *
 * <pre>
 * Scenario: Map Customer A to Target A v1.0
 *   When the mapping of the pair is saved through the API
 *   Then it is stored for that pair only and re-renders exactly as left
 *
 * Scenario: Add, change and delete elements
 *   When a link is added, changed and deleted in successive saves
 *   Then the stored mapping reflects all three edits
 *
 * &#64;negative Scenario: Two targets never share one mapping
 *   When Target A's mapping is changed
 *   Then Target B's mapping is unchanged (separate rows, separate versions)
 *
 * &#64;negative Scenario: A link to a field the target contract does not have is refused
 *   Then the API refuses it and names the field
 *
 * Scenario: Matching pins publish
 *
 * &#64;negative Scenario Outline: A pin mismatch is refused and named
 *   Examples: source contract 1.0 | a target contract version other than the route's
 * </pre>
 *
 * <p>ADR-013 conformance legs ride along: version rows are deduplicated by content (two identical saves create
 * 0 rows, a changed expression exactly 1 with version + 1), and the application role can neither UPDATE nor
 * DELETE a mapping version (42501). Each test works on its own freshly created flow, so no test sees another's
 * rows. The source contract versions are seeded BEFORE the flow is created: the flow create pins the newest
 * label ({@code getOrCreateSourceDraftVersion}) and the version guard forbids re-pinning, so the outline's
 * premise (an interface version pinned to 1.1 whose mappings may be bound to 1.0 or 1.1) is built by seeding
 * 1.0 and 1.1 approved first. The target contract versions are seeded (once, by a stable name) as owner the
 * way the registry phase's fixtures do.</p>
 */
@Tag("container")
@SpringBootTest(classes = E3LaneCSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class T190MappingPinContainerTest {

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

    // ------------------------------------------------------------------ the mapping save/read API

    @Test
    @DisplayName("Scenario: Map Customer A to Target A v1.0 - stored for that pair only, re-rendered as left")
    void mapCustomerAToTargetA() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID routeA = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID routeB = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 2);
        UUID source = sourceVersionOf(draft.sourceSystem(), "1.0");
        UUID targetA = targetVersion(systemId(draft.destination1()), "customer-" + draft.flowId());
        String bearer = actor.author();

        Response saved = ConfigAdminApi.call("PUT", routesPath(base, routeA), bearer, mappingBody(
                "[{\"source\":\"customerId\",\"target\":\"customer_code\"},"
                        + "{\"source\":\"orderId\",\"target\":\"order_number\"},"
                        + "{\"source\":\"amount\",\"target\":\"total_amount\"}]",
                "{\"customer_code\": customerId, \"order_number\": orderId, \"total_amount\": amount}",
                source, targetA));
        assertThat(saved.status()).as("the mapping saves, body: %s", saved.body()).isEqualTo(200);

        Response readBack = ConfigAdminApi.call("GET", routesPath(base, routeA), bearer, null);
        assertThat(readBack.status()).as("the mapping reads back, body: %s", readBack.body()).isEqualTo(200);
        JsonNode body = readBack.json();
        assertThat(body.path("fieldMappings").toString())
                .as("the mapping re-renders exactly as left, link for link in the saved order").isEqualTo(
                        "[{\"source\":\"customerId\",\"target\":\"customer_code\"},"
                                + "{\"source\":\"orderId\",\"target\":\"order_number\"},"
                                + "{\"source\":\"amount\",\"target\":\"total_amount\"}]");
        assertThat(body.path("compiledExpression").asString()).isEqualTo(
                "{\"customer_code\": customerId, \"order_number\": orderId, \"total_amount\": amount}");
        assertThat(body.path("sourceContractVersionId").asString())
                .as("the bound source contract version is carried").isEqualTo(source.toString());
        assertThat(body.path("targetContractVersionId").asString())
                .as("the bound target contract version (Target A v1.0) is carried").isEqualTo(targetA.toString());

        // Stored for that pair only: the other route's mapping is still the untouched default.
        JsonNode other = ConfigAdminApi.call("GET", routesPath(base, routeB), bearer, null).json();
        assertThat(other.path("fieldMappings").toString()).as("the other pair's mapping was not touched")
                .isEqualTo("[]");
        assertThat(other.path("compiledExpression").asString()).isEqualTo("$");
        assertThat(other.path("targetContractVersionId").isNull() || other.path("targetContractVersionId")
                .isMissingNode()).as("the other pair carries no target binding").isTrue();
    }

    @Test
    @DisplayName("Scenario: Add, change and delete elements - all three edits are stored")
    void addChangeAndDeleteElementsAreStored() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID source = sourceVersionOf(draft.sourceSystem(), "1.0");
        UUID target = targetVersion(systemId(draft.destination1()), "edits-" + draft.flowId());
        String bearer = actor.author();

        assertThat(ConfigAdminApi.call("PUT", routesPath(base, route), bearer, mappingBody(
                "[{\"source\":\"a\",\"target\":\"customer_code\"},{\"source\":\"b\",\"target\":\"order_number\"}]",
                "$.added", source, target)).status()).as("the add saves").isEqualTo(200);
        assertThat(ConfigAdminApi.call("PUT", routesPath(base, route), bearer, mappingBody(
                "[{\"source\":\"a\",\"target\":\"customer_code\"},{\"source\":\"b\",\"target\":\"total_amount\"}]",
                "$.changed", source, target)).status()).as("the change saves").isEqualTo(200);
        assertThat(ConfigAdminApi.call("PUT", routesPath(base, route), bearer, mappingBody(
                "[{\"source\":\"a\",\"target\":\"customer_code\"}]", "$.deleted", source, target)).status())
                .as("the delete saves").isEqualTo(200);

        JsonNode readBack = ConfigAdminApi.call("GET", routesPath(base, route), bearer, null).json();
        assertThat(readBack.path("fieldMappings").toString())
                .as("the stored mapping reflects all three edits: one link kept (changed earlier), one deleted")
                .isEqualTo("[{\"source\":\"a\",\"target\":\"customer_code\"}]");
        assertThat(readBack.path("compiledExpression").asString()).isEqualTo("$.deleted");
    }

    @Test
    @Tag("negative")
    @DisplayName("Scenario: Two targets never share one mapping - separate rows, separate versions")
    void twoTargetsNeverShareOneMapping() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID routeA = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID routeB = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 2);
        UUID source = sourceVersionOf(draft.sourceSystem(), "1.0");
        UUID targetA = targetVersion(systemId(draft.destination1()), "pairA-" + draft.flowId());
        UUID targetB = targetVersion(systemId(draft.destination2()), "pairB-" + draft.flowId());
        String bearer = actor.author();

        assertThat(ConfigAdminApi.call("PUT", routesPath(base, routeA), bearer, mappingBody(
                "[{\"source\":\"a\",\"target\":\"customer_code\"}]", "$.a", source, targetA)).status())
                .as("Target A's mapping saves").isEqualTo(200);
        assertThat(ConfigAdminApi.call("PUT", routesPath(base, routeB), bearer, mappingBody(
                "[{\"source\":\"b\",\"target\":\"total_amount\"}]", "$.b", source, targetB)).status())
                .as("Target B's mapping saves").isEqualTo(200);

        // Changing Target A's mapping leaves Target B's untouched.
        assertThat(ConfigAdminApi.call("PUT", routesPath(base, routeA), bearer, mappingBody(
                "[{\"source\":\"a-changed\",\"target\":\"order_number\"}]", "$.a2", source, targetA)).status())
                .as("Target A's mapping changes").isEqualTo(200);
        JsonNode other = ConfigAdminApi.call("GET", routesPath(base, routeB), bearer, null).json();
        assertThat(other.path("fieldMappings").toString())
                .as("Target B's mapping is unchanged").isEqualTo("[{\"source\":\"b\",\"target\":\"total_amount\"}]");
        assertThat(other.path("compiledExpression").asString()).isEqualTo("$.b");

        // Separate rows, separate versions: each pair owns its own version chain - Target A's chain grew by
        // exactly its own change (two versions), Target B's stays at its one save.
        assertThat(versionCount(mappingIdOf(routeA))).as("Target A's chain grows by its own save").isEqualTo(2);
        assertThat(versionCount(mappingIdOf(routeB))).as("Target B's chain stays at one save").isEqualTo(1);
        assertThat(mappingIdOf(routeA)).as("two live rows").isNotEqualTo(mappingIdOf(routeB));
    }

    @Test
    @Tag("negative")
    @DisplayName("Scenario: A link to a field the target contract does not have is refused and names the field")
    void linkToAbsentTargetFieldIsRefused() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID source = sourceVersionOf(draft.sourceSystem(), "1.0");
        UUID targetA = targetVersion(systemId(draft.destination1()), "fields-" + draft.flowId());
        String bearer = actor.author();

        Response baseline = ConfigAdminApi.call("PUT", routesPath(base, route), bearer, mappingBody(
                "[{\"source\":\"keep\",\"target\":\"customer_code\"}]", "$.keep", source, targetA));
        assertThat(baseline.status()).as("the honest mapping saves first, body: %s", baseline.body())
                .isEqualTo(200);

        Response refused = ConfigAdminApi.call("PUT", routesPath(base, route), bearer, mappingBody(
                "[{\"source\":\"x\",\"target\":\"no_such_field\"}]", "$.x", source, targetA));
        assertThat(refused.status()).as("the linked-to field is refused, body: %s", refused.body())
                .isEqualTo(400);
        assertThat(refused.body()).as("the refusal names the field").contains("no_such_field");

        JsonNode readBack = ConfigAdminApi.call("GET", routesPath(base, route), bearer, null).json();
        assertThat(readBack.path("fieldMappings").toString())
                .as("the stored mapping is unchanged by the refused save")
                .isEqualTo("[{\"source\":\"keep\",\"target\":\"customer_code\"}]");
    }

    // ------------------------------------------------------------------ the T-190 publish-time pin check

    @Test
    @DisplayName("Scenario: Matching pins publish")
    void matchingPinsPublish() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0", "1.1");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID otherRoute = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 2);
        UUID pinned = sourceVersionOf(draft.sourceSystem(), "1.1");
        bind(route, pinned, targetVersion(systemId(draft.destination1()), "match-a-" + draft.flowId()));
        bind(otherRoute, pinned, targetVersion(systemId(draft.destination2()), "match-b-" + draft.flowId()));

        LaneCLifecycle.saveFlowDraft(base, actor.author(), draft.flowId(), draft.sourceSystem(),
                draft.destinations());
        LaneCLifecycle.submit(base, actor, draft.flowId(), "T-190: pins match");
        Response approved = LaneCLifecycle.approve(base, actor.approver(), draft.flowId());
        assertThat(approved.status()).as("the version is approved, body: %s", approved.body()).isEqualTo(200);
        Response published = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.FLOWS + "/" + draft.flowId() + "/publish", actor.approver(), "{}");
        assertThat(published.status()).as("matching pins publish, body: %s", published.body()).isEqualTo(200);
    }

    @Test
    @Tag("negative")
    @DisplayName("A pin mismatch on the source is refused and names the route and both versions (nothing published)")
    void sourcePinMismatchIsRefusedAndNamed() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0", "1.1");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID otherRoute = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 2);
        // The flow create pinned 1.1 (the newest seeded label); the route's mapping stays bound to 1.0.
        UUID pinned = sourceVersionOf(draft.sourceSystem(), "1.1");
        bind(route, sourceVersionOf(draft.sourceSystem(), "1.0"),
                targetVersion(systemId(draft.destination1()), "srcdrift-" + draft.flowId()));
        bind(otherRoute, pinned, targetVersion(systemId(draft.destination2()), "srcok-" + draft.flowId()));

        LaneCLifecycle.saveFlowDraft(base, actor.author(), draft.flowId(), draft.sourceSystem(),
                draft.destinations());
        LaneCLifecycle.submit(base, actor, draft.flowId(), "T-190: source drift");
        Response approved = LaneCLifecycle.approve(base, actor.approver(), draft.flowId());
        assertThat(approved.status()).as("the version is approved").isEqualTo(200);

        Response refused = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.FLOWS + "/" + draft.flowId() + "/publish", actor.approver(), "{}");
        assertThat(refused.status()).as("the mismatch is refused, body: %s", refused.body()).isEqualTo(409);
        assertThat(refused.body()).as("the refusal names the route and both versions")
                .contains(route.toString()).contains("1.0").contains("1.1");
        assertThat(LaneCLifecycle.versionStatus(draft.flowId(), 2)).as("nothing is published")
                .isEqualTo("APPROVED");
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select published_version from public.interface where id = ?::uuid")) {
            read.setString(1, draft.flowId().toString());
            try (ResultSet rs = read.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).as("the published pointer is untouched").isNull();
            }
        }
    }

    @Test
    @Tag("negative")
    @DisplayName("A target bound to another system's contract version is refused and named (nothing published)")
    void targetPinMismatchIsRefusedAndNamed() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0", "1.1");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID otherRoute = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 2);
        UUID routesTarget = targetVersion(systemId(draft.destination1()), "tgtdrift-a-" + draft.flowId());
        UUID otherTarget = targetVersion(systemId(draft.destination2()), "tgtdrift-b-" + draft.flowId());
        UUID pinned = sourceVersionOf(draft.sourceSystem(), "1.1");
        bind(route, pinned, otherTarget);
        bind(otherRoute, pinned, routesTarget);

        LaneCLifecycle.saveFlowDraft(base, actor.author(), draft.flowId(), draft.sourceSystem(),
                draft.destinations());
        LaneCLifecycle.submit(base, actor, draft.flowId(), "T-190: target drift");
        Response approved = LaneCLifecycle.approve(base, actor.approver(), draft.flowId());
        assertThat(approved.status()).as("the version is approved").isEqualTo(200);

        Response refused = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.FLOWS + "/" + draft.flowId() + "/publish", actor.approver(), "{}");
        assertThat(refused.status()).as("the mismatch is refused, body: %s", refused.body()).isEqualTo(409);
        assertThat(refused.body()).as("the refusal names the route and both versions")
                .contains(route.toString()).contains(otherTarget.toString()).contains(routesTarget.toString());
        assertThat(LaneCLifecycle.versionStatus(draft.flowId(), 2)).as("nothing is published")
                .isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ ADR-013 conformance legs

    @Test
    @DisplayName("Two saves with no mapping change create 0 version rows; a changed expression exactly 1 with version + 1")
    void versionsAreDeduplicatedByContent() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID source = sourceVersionOf(draft.sourceSystem(), "1.0");
        UUID target = targetVersion(systemId(draft.destination1()), "dedup-" + draft.flowId());
        String bearer = actor.author();
        String unchanged = mappingBody("[{\"source\":\"v\",\"target\":\"customer_code\"}]", "$.v1", source, target);

        assertThat(ConfigAdminApi.call("PUT", routesPath(base, route), bearer, unchanged).status())
                .as("the first save").isEqualTo(200);
        int rowsAfterFirst = versionCount(mappingIdOf(route));
        assertThat(ConfigAdminApi.call("PUT", routesPath(base, route), bearer, unchanged).status())
                .as("the identical save").isEqualTo(200);
        assertThat(versionCount(mappingIdOf(route)))
                .as("an unchanged save creates 0 new version rows").isEqualTo(rowsAfterFirst);

        String changed = mappingBody("[{\"source\":\"v\",\"target\":\"order_number\"}]", "$.v2", source, target);
        assertThat(ConfigAdminApi.call("PUT", routesPath(base, route), bearer, changed).status())
                .as("the changed save").isEqualTo(200);
        assertThat(versionCount(mappingIdOf(route)))
                .as("a changed expression creates exactly 1 new version row").isEqualTo(rowsAfterFirst + 1);
        assertThat(newestVersionNumber(mappingIdOf(route)))
                .as("the newest version number is the previous + 1").isEqualTo(rowsAfterFirst + 1);
        assertThat(ConfigAdminApi.call("GET", routesPath(base, route), bearer, null).json()
                .path("mappingVersionId").asString().isEmpty())
                .as("the mapping carries its current version id").isFalse();
    }

    @Test
    @Tag("negative")
    @DisplayName("UPDATE and DELETE on mapping_version as the application role fail with 42501")
    void appRoleCannotChangeMappingVersions() throws Exception {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = actors();
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        bind(route, sourceVersionOf(draft.sourceSystem(), "1.0"),
                targetVersion(systemId(draft.destination1()), "immut-" + draft.flowId()));
        UUID mappingId = mappingIdOf(route);

        try (Connection app = DriverManager.getConnection(
                E3ConfigDatabase.postgres().getJdbcUrl(), login.username(), login.password())) {
            LaneCLifecycle.stampTenant(app);
            try (PreparedStatement update = app.prepareStatement(
                    "update public.mapping_version set compiled_expression = 'forged' where mapping_id = ?::uuid")) {
                update.setString(1, mappingId.toString());
                Exception thrown = catchExecute(update);
                assertThat(thrown).as("the UPDATE is refused").isInstanceOf(SQLException.class);
                assertThat(sqlState(thrown)).as("the row-level security answers, not only a grant").isEqualTo("42501");
            }
            try (PreparedStatement delete = app.prepareStatement(
                    "delete from public.mapping_version where mapping_id = ?::uuid")) {
                delete.setString(1, mappingId.toString());
                Exception thrown = catchExecute(delete);
                assertThat(thrown).as("the DELETE is refused").isInstanceOf(SQLException.class);
                assertThat(sqlState(thrown)).isEqualTo("42501");
            }
        }
    }

    @Test
    @DisplayName("A mapping of a route the tenant cannot see answers 404, both reads and writes")
    void foreignRouteAnswers404() {
        String base = ConfigAdminApi.baseUrl(port);
        String bearer = actors().author();
        Response get = ConfigAdminApi.call("GET", routesPath(base, UUID.randomUUID()), bearer, null);
        assertThat(get.status()).as("GET a foreign route's mapping").isEqualTo(404);
        Response put = ConfigAdminApi.call("PUT", routesPath(base, UUID.randomUUID()), bearer,
                mappingBody("[]", "$", UUID.randomUUID(), UUID.randomUUID()));
        assertThat(put.status()).as("PUT onto a foreign route").isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A draft flow of one source and two destinations whose source contract versions are already approved:
     * the systems are created, {@code labels} are seeded APPROVED, then the flow is created - the flow create
     * pins the newest seeded label, because the version guard forbids re-pinning a draft later.
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
            sourceVersionOf(sourceSystem, label);
        }
        return new LaneCLifecycle.Draft(
                LaneCLifecycle.createFlow(base, actor.author(), sourceSystem,
                        List.of(destination1, destination2), "$.orderId"),
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
     * when missing (deterministic id; reading back own seed is the second call's answer). The source contract
     * carries the system's code, the way the flow create's {@code getOrCreateSourceDraftVersion} finds it, so
     * a flow created AFTER these seeds pins the newest of them - the outline's pinned-source premise, built
     * without touching the version guard.
     */
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
                create.setString(5, "T-190 source contract of " + sourceSystem);
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
     * id - the registry phase's approved shape, owner-seeded the way the denial suite seeds contracts.
     */
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

    private UUID mappingIdOf(UUID routeId) throws SQLException {
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

    private int versionCount(UUID mappingId) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select count(*) from public.mapping_version where mapping_id = ?::uuid")) {
            read.setString(1, mappingId.toString());
            try (ResultSet rs = read.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int newestVersionNumber(UUID mappingId) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select version from public.mapping_version where mapping_id = ?::uuid"
                                + " order by version desc limit 1")) {
            read.setString(1, mappingId.toString());
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).as("the mapping has version rows").isTrue();
                return rs.getInt(1);
            }
        }
    }

    private static Exception catchExecute(PreparedStatement statement) {
        try {
            statement.executeUpdate();
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private static String sqlState(Exception thrown) {
        Throwable cause = thrown;
        while (cause != null) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }
}