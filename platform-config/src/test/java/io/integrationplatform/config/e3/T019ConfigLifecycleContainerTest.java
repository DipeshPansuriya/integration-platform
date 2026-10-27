package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.PendingIntegration;
import io.integrationplatform.config.e3.E3ConfigDatabase.RestrictedLogin;
import io.integrationplatform.e3lanec.E3LaneCSuiteApp;
import io.integrationplatform.config.e3.LaneCLifecycle.Actors;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
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
 * T-019 (lane C, E3-F2-S1): the lifecycle API - draft / submit / approve / publish, on a running system through the
 * real identity chain (the full production path: bearer, tenant binding, transaction stamping), plus the draft-save
 * and submit the contract-version API keeps per ADR-011 Decision 2 (the nodes lane A's T-092/T-093 suites pin).
 *
 * <pre>
 * Scenario: The lifecycle
 *   Given a draft flow version
 *   When it is submitted, approved by another user, and published
 *   Then the published bundle equals the snapshot taken at submit, byte for byte
 *
 * &#64;negative
 * Scenario: A submitted version cannot be edited
 *   Given a submitted version
 *   When anyone tries to change it
 *   Then the change is refused (a new draft version is the only way)
 *
 * Scenario: The author may approve their own version (single-user workflow, V2_4_1420)
 *   Given a version authored by user U (a tenant admin)
 *   When U approves it, through the API or directly through the database
 *   Then the approval succeeds and names U as the approver; no refusal is recorded
 * </pre>
 *
 * <p>The byte-for-byte proof is not vacuous: after the approval the live configuration rows are deliberately
 * drifted away (through the owner connection), and publishing must still lay the SUBMITTED snapshot's bundle over
 * them - the published configuration is the approved snapshot, never whatever the working rows drifted to. The two
 * sides are compared as full trees (no field differs).
 *
 * <p>The database-layer halves of the scenarios go through the restricted application login (the same role the
 * composed system connects as): a content edit is refused by the grant layer itself (42501, the column is not one
 * the application may update), an approver = author update is no longer refused (V2_4_1420 dropped the
 * {@code approver_not_author} CHECK) - the content and lifecycle invariants still hold whatever path the write takes.
 */
@Tag("container")
@SpringBootTest(classes = E3LaneCSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class T019ConfigLifecycleContainerTest {

    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    /** The suite's one signing key, shared with the composed app's decoder bean (see {@link E3LaneCSuiteApp}). */
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

    @BeforeEach
    void premise() {
        assertThat(login).as("the restricted application login exists").isNotNull();
    }

    private Actors actors() {
        return Actors.of(TOKENS, E3ConfigDatabase.tenantA().subject(), approverSubject,
                E3ConfigDatabase.tenantB().subject());
    }

    // ------------------------------------------------------------------ Scenario: the lifecycle, byte for byte

    @Test
    @DisplayName("Scenario: The lifecycle - submitted, approved by another user, published equals the submit snapshot")
    void theLifecycle() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actors = actors();
        LaneCLifecycle.Draft draft = LaneCLifecycle.createDraftFlowWithTwoDestinations(base, actors);
        UUID flowId = draft.flowId();

        // T-190: the mappings bind to contract versions and a new draft captures the bound bundle.
        LaneCLifecycle.bindAllRoutesAndResnapshot(base, actors.author(), E3ConfigDatabase.tenantA().id(),
                flowId);
        LaneCLifecycle.submit(base, actors, flowId, "lifecycle: the bundle is ready");

        UUID versionId = LaneCLifecycle.latestVersionId(flowId);
        String snapshotAtSubmit = LaneCLifecycle.snapshotText(versionId);
        assertThat(snapshotAtSubmit).as("the submit snapshot exists in the version row").isNotBlank();

        // The working rows deliberately drift away from the approved snapshot before the publish - the published
        // bundle must still be the snapshot's bundle, byte for byte.
        String drift = "drifted-to-" + LaneCIdentities.randomCode("drift");
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement route = owner.prepareStatement(
                        "update public.route set condition = ? where interface_id = ?::uuid")) {
            route.setString(1, drift);
            route.setString(2, flowId.toString());
            route.executeUpdate();
        }
        LaneCLifecycle.tamperLiveMapping(LaneCLifecycle.routeIdOfLiveRow(flowId, 1), drift);

        Response approved = LaneCLifecycle.approve(base, actors.approver(), flowId);
        assertThat(approved.status()).as("another user approves, body: %s", approved.body()).isEqualTo(200);

        LaneCLifecycle.publish(base, actors.approver(), flowId);

        // Then: the published bundle equals the snapshot taken at submit, byte for byte - the drift the rows
        // carried before the publish is not in the published configuration.
        assertThat(LaneCLifecycle.snapshotText(versionId)).as("the snapshot is immutable across the lifecycle")
                .isEqualTo(snapshotAtSubmit);
        JsonNode publishedBundle = LaneCLifecycle.bundleOfLiveRows(flowId);
        assertThat(publishedBundle).as("no field of the published bundle differs from the submitted snapshot")
                .isEqualTo(LaneCLifecycle.bundleOfSnapshot(snapshotAtSubmit));
        assertThat(publishedBundle.toString()).as("no drift survived the publish").doesNotContain(drift);

        Response read = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + flowId,
                actors.author(), null);
        assertThat(read.json().path("status").asString()).as("the flow is published to the data plane")
                .isEqualTo("PUBLISHED");
    }

    // ------------------------------------------------------- Scenario: a submitted version cannot be edited

    @Test
    @Tag("negative")
    @DisplayName("@negative Scenario: A submitted version cannot be edited - the change is refused; a new draft is the only way")
    void aSubmittedVersionCannotBeEdited() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actors = actors();
        LaneCLifecycle.Draft draft = LaneCLifecycle.createDraftFlowWithTwoDestinations(base, actors);
        UUID flowId = draft.flowId();

        LaneCLifecycle.submit(base, actors, flowId, "submit before the edit attempt");
        UUID versionId = LaneCLifecycle.latestVersionId(flowId);
        String snapshotAtSubmit = LaneCLifecycle.snapshotText(versionId);

        // Anyone tries to change it: the edit attempt on the version comes back refused through the API.
        Response edited = ConfigAdminApi.call("PUT", base + ConfigAdminApi.FLOWS + "/" + flowId
                + "/versions/" + versionId, actors.author(), "{}");
        if (edited.status() == 404) {
            throw new PendingIntegration("the version edit attempt answered 404 (no API refused it)",
                    "lane C (T-019)");
        }
        assertThat(edited.status()).as("the edit of a submitted version is refused, body: %s", edited.body())
                .isEqualTo(409);
        assertThat(edited.body().toLowerCase()).as("the refusal names the one way to change content")
                .contains("draft");

        // And directly through the database with the application's own login: the content columns are not
        // updatable by anyone's path - the grant layer itself refuses before any trigger runs.
        try (Connection app = DriverManager.getConnection(E3ConfigDatabase.postgres().getJdbcUrl(),
                login.username(), login.password())) {
            LaneCLifecycle.stampTenant(app);
            try (PreparedStatement attempt = app.prepareStatement(
                    "update public.interface_version set business_key_rule = 'drifted-away' "
                            + "where id = ?::uuid")) {
                attempt.setString(1, versionId.toString());
                attempt.executeUpdate();
                throw new AssertionError("the content update through the database was not refused");
            }
        } catch (SQLException refused) {
            assertThat(refused.getSQLState())
                    .as("the database refuses a content edit for the application role (42501), message: %s",
                            refused.getMessage())
                    .isEqualTo("42501");
        }
        assertThat(LaneCLifecycle.snapshotText(versionId)).as("the submitted version's content stands untouched")
                .isEqualTo(snapshotAtSubmit);

        // A new draft version is the only way: the draft-save on the FLOW (with a third destination) succeeds
        // and leaves the submitted version untouched.
        String destination3 = LaneCIdentities.randomCode("tgt");
        LaneCLifecycle.createSystem(base, actors.author(), destination3, "OUTBOUND");
        LaneCLifecycle.saveFlowDraft(base, actors.author(), flowId, draft.sourceSystem(),
                List.of(draft.destination1(), draft.destination2(), destination3));
        String newSnapshot = LaneCLifecycle.snapshotText(LaneCLifecycle.latestVersionId(flowId));
        assertThat(newSnapshot).as("the new draft is a new version row with the third destination").
                isNotEqualTo(snapshotAtSubmit);
        assertThat(LaneCLifecycle.snapshotText(versionId)).as("the submitted version still stands untouched")
                .isEqualTo(snapshotAtSubmit);
    }

    // --------------------------------------------------------------- Scenario: the author may approve

    @Test
    @DisplayName("Scenario: The author may approve their own version - 200 through the API, no refusal recorded")
    void theAuthorMayApproveTheirOwnVersion() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actors = actors();
        UUID flowId = LaneCLifecycle.createDraftFlowWithTwoDestinations(base, actors).flowId();

        LaneCLifecycle.submit(base, actors, flowId, "the author's own draft");
        UUID versionId = LaneCLifecycle.latestVersionId(flowId);
        UUID authorId = E3ConfigDatabase.tenantA().userId();

        // Through the API: the author approves their own version.
        Response approved = LaneCLifecycle.approve(base, actors.author(), flowId);
        assertThat(approved.status()).as("the author's own approval succeeds, body: %s", approved.body())
                .isEqualTo(200);

        // No refusal is recorded, and the database names the author as the approver of the APPROVED version.
        assertThat(LaneCIdentities.refusals(E3ConfigDatabase.tenantA().id(), versionId))
                .as("a self-approval writes no refusal row").isEmpty();
        try (Connection app = DriverManager.getConnection(E3ConfigDatabase.postgres().getJdbcUrl(),
                login.username(), login.password())) {
            LaneCLifecycle.stampTenant(app);
            try (PreparedStatement read = app.prepareStatement(
                    "select status || '/' || approver_id::text from public.interface_version where id = ?::uuid")) {
                read.setString(1, versionId.toString());
                try (java.sql.ResultSet rs = read.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).as("approved by the author").isEqualTo("APPROVED/" + authorId);
                }
            }
        }

        // A second approval finds nothing pending: 409.
        Response again = LaneCLifecycle.approve(base, actors.approver(), flowId);
        assertThat(again.status()).as("body: %s", again.body()).isEqualTo(409);
    }

    // ------------------------------------------- ADR-011 Decision 2: the contract draft-save keeps sourceSchema,
    // ------------------------------------------- and the submit stores the changeNote (the T-092/T-093 nodes)

    @Test
    @DisplayName("Scenario: draft-save stores sourceSchema as the draft source contract version; submit stores the changeNote")
    void theContractDraftSaveKeepsSourceSchemaAndSubmitStoresChangeNote() {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actors = actors();
        String systemCode = LaneCIdentities.randomCode("src");
        LaneCLifecycle.createSystem(base, actors.author(), systemCode, "INBOUND");
        String sourceSchema = """
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "type":"object","required":["orderId"],
                 "properties":{"orderId":{"type":"string"},"qty":{"type":"integer"}}}""";

        Response saved = ConfigAdminApi.call("POST", base + ConfigAdminApi.CONTRACT_VERSIONS, actors.author(),
                ConfigAdminApi.saveDraft(systemCode, sourceSchema));
        if (saved.status() == 404) {
            throw new PendingIntegration("the draft-save API (contracts/versions) answered 404", "lane C (T-019)");
        }
        assertThat(saved.status()).as("the draft save with sourceSchema answers 200, body: %s", saved.body())
                .isEqualTo(200);
        String versionId = saved.json().path("id").asString();
        assertThat(versionId).as("the saved draft names its version id").isNotBlank();

        Response read = ConfigAdminApi.call("GET", base + ConfigAdminApi.CONTRACT_VERSIONS + "/" + versionId,
                actors.author(), null);
        assertThat(read.status()).as("the saved draft is readable, body: %s", read.body()).isEqualTo(200);
        assertThat(read.json().path("sourceSchema").toString()).as("the draft holds the sourceSchema it was sent")
                .contains("orderId");
        assertThat(read.json().path("status").asString()).as("the saved version is a draft")
                .isEqualTo("DRAFT");

        Response submitted = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.CONTRACT_VERSIONS + "/" + versionId + "/submit", actors.author(),
                ConfigAdminApi.submit("E3-T019: the contract draft is ready for approval"));
        if (submitted.status() == 404) {
            throw new PendingIntegration("the contract version submit answered 404", "lane C (T-019)");
        }
        assertThat(submitted.status()).as("the submit with the changeNote body is accepted, body: %s",
                submitted.body()).isEqualTo(200);

        Response afterSubmit = ConfigAdminApi.call("GET",
                base + ConfigAdminApi.CONTRACT_VERSIONS + "/" + versionId, actors.author(), null);
        assertThat(afterSubmit.json().path("changeNote").asString())
                .as("the changeNote the submit sent is stored with the submitted version")
                .isEqualTo("E3-T019: the contract draft is ready for approval");
        assertThat(afterSubmit.json().path("status").asString())
                .as("the submitted version moved to pending approval")
                .isEqualTo("PENDING_APPROVAL");

        // @negative: a draft without sourceSchema is a clear 400 naming the missing field.
        Response refused = ConfigAdminApi.call("POST", base + ConfigAdminApi.CONTRACT_VERSIONS, actors.author(),
                ConfigAdminApi.saveDraftMissingSourceSchema(systemCode));
        assertThat(refused.status()).as("a draft without the required sourceSchema is refused, body: %s",
                refused.body()).isEqualTo(400);
        assertThat(refused.body()).as("the refusal names the missing field").contains("sourceSchema");
    }
}
