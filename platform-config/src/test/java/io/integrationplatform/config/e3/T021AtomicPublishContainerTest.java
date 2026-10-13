package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.RestrictedLogin;
import io.integrationplatform.e3lanec.E3LaneCSuiteApp;
import io.integrationplatform.config.e3.LaneCLifecycle.Actors;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
 * T-021 (lane C, E3-F2-S2): publishing is atomic - the interface, its routes and its mappings land in ONE
 * transaction, or none of them does, and a failed publish leaves the PREVIOUS published version live.
 *
 * <pre>
 * Scenario: A publish writes all three parts or nothing
 *   Given a draft version whose bundle has an interface, routes and mappings
 *   When it publishes
 *   Then the interface, the routes and the mappings are written in one transaction
 *   And a bundle whose mapping write fails mid-way leaves none of the three changed
 *
 * &#64;negative
 * Scenario: A failed publish leaves the previous published version live
 *   Given version 1 is published and version 2 is approved
 *   When publishing version 2 fails part-way
 *   Then version 1 stays published (the data plane resolves the old bundle until a whole new one lands)
 * </pre>
 *
 * <p>The mid-way failure is injected on the REAL database, not a repository mock (E3-C6): the owner connection
 * installs a {@code BEFORE INSERT OR UPDATE} trigger on {@code public.mapping} that raises when the exact
 * sentinel expression arrives with the new bundle. The bundle carries route 3's bound sentinel mapping, so
 * the pin check passes and the publish's mapping write takes the UPDATE path, where the trigger fires.
 * Everything the failed
 * transaction touched (route rows, version statuses, the interface pointer) must roll back; the trigger is
 * removed in a finally block so no other suite inherits it.
 */
@Tag("container")
@SpringBootTest(classes = E3LaneCSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class T021AtomicPublishContainerTest {

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

    private Actors actors() {
        return Actors.of(TOKENS, E3ConfigDatabase.tenantA().subject(), approverSubject,
                E3ConfigDatabase.tenantB().subject());
    }

    // ------------------------------------------------------------------ Scenario: all three parts or nothing

    @Test
    @DisplayName("Scenario: A publish writes the interface, the routes and the mappings in one transaction")
    void aPublishWritesAllThreePartsAsOneUnit() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actors = actors();
        LaneCLifecycle.Draft draft = LaneCLifecycle.createDraftFlowWithTwoDestinations(base, actors);
        UUID flowId = draft.flowId();

        LaneCLifecycle.bindAllRoutesAndResnapshot(base, actors.author(), E3ConfigDatabase.tenantA().id(),
                flowId);
        LaneCLifecycle.submitApprovePublish(base, actors, flowId, "T-021: the bound bundle is complete");

        // The interface part: the published pointer and the live status, in the interface row.
        JsonNode interfaceRow = LaneCLifecycle.interfaceRow(flowId);
        assertThat(interfaceRow.path("status").asString()).as("the interface is published")
                .isEqualTo("PUBLISHED");
        assertThat(interfaceRow.path("published_version").asString())
                .as("the bound version is the published pointer")
                .isEqualTo("2");

        // The routes and mappings parts: the live rows carry exactly the published snapshot's bundle.
        String snapshotV1 = LaneCLifecycle.snapshotText(LaneCLifecycle.latestVersionId(flowId)); // the bound v2
        assertThat(LaneCLifecycle.bundleOfLiveRows(flowId))
                .as("routes and mappings landed as the snapshot holds them")
                .isEqualTo(LaneCLifecycle.bundleOfSnapshot(snapshotV1));
        assertThat(LaneCLifecycle.countRows("route", flowId)).as("both destination routes are live").isEqualTo(2);
        assertThat(LaneCLifecycle.countRows("mapping", flowId)).as("each route carries its mapping").isEqualTo(2);

        // A second complete publish: all three parts move together again - interface pointer 2, the new route
        // and mapping for the third destination, the old version archived.
        String destination3 = LaneCIdentities.randomCode("tgt");
        LaneCLifecycle.createSystem(base, actors.author(), destination3, "OUTBOUND");
        LaneCLifecycle.saveFlowDraft(base, actors.author(), flowId, draft.sourceSystem(),
                List.of(draft.destination1(), draft.destination2(), destination3));
        // T-190: the new route's mapping is bound and the bundle re-snapshotted before the publish - an
        // unbound mapping refuses the publish (ADR-011 rule 6).
        LaneCLifecycle.bindAllRoutesAndResnapshot(base, actors.author(), E3ConfigDatabase.tenantA().id(),
                flowId);
        LaneCLifecycle.submitApprovePublish(base, actors, flowId, "T-021: the bound v3 adds a destination");

        JsonNode afterSecond = LaneCLifecycle.interfaceRow(flowId);
        assertThat(afterSecond.path("published_version").asString())
                .as("one transaction moved the interface pointer to the new bound version")
                .isEqualTo("4");
        assertThat(LaneCLifecycle.countRows("route", flowId)).as("the third route landed with the pointer")
                .isEqualTo(3);
        assertThat(LaneCLifecycle.countRows("mapping", flowId)).as("and its mapping landed with them")
                .isEqualTo(3);
        assertThat(LaneCLifecycle.versionStatus(flowId, 2)).as("the superseded version is archived")
                .isEqualTo("ARCHIVED");
        assertThat(LaneCLifecycle.versionStatus(flowId, 4)).as("the new version is published")
                .isEqualTo("PUBLISHED");
        String snapshotV2 = LaneCLifecycle.snapshotText(LaneCLifecycle.latestVersionId(flowId)); // the bound v4
        assertThat(LaneCLifecycle.bundleOfLiveRows(flowId))
                .as("the live bundle equals the published snapshot again")
                .isEqualTo(LaneCLifecycle.bundleOfSnapshot(snapshotV2));
    }

    // ------------------------------------------------- @negative: a failed publish changes none of the three

    @Test
    @Tag("negative")
    @DisplayName("@negative Scenario: A bundle whose mapping write fails mid-way leaves none of the three changed")
    void aFailedPublishRollsBackEveryPart() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actors = actors();
        LaneCLifecycle.Draft draft = LaneCLifecycle.createDraftFlowWithTwoDestinations(base, actors);
        UUID flowId = draft.flowId();
        LaneCLifecycle.bindAllRoutesAndResnapshot(base, actors.author(), E3ConfigDatabase.tenantA().id(),
                flowId);
        LaneCLifecycle.submitApprovePublish(base, actors, flowId, "T-021: the bound bundle stays live");

        // Version 2 is a superseded draft (the first save), version 3 the failing bundle: every save is a new
        // version row. Its live mapping is drifted to the sentinel, then the mapping and route rows removed -
        // so the publish of the snapshot (which carries them) takes the INSERT path.
        String destination3 = LaneCIdentities.randomCode("tgt");
        String sentinel = "T021-SENTINEL-" + LaneCIdentities.randomCode("abort");
        LaneCLifecycle.createSystem(base, actors.author(), destination3, "OUTBOUND");
        LaneCLifecycle.saveFlowDraft(base, actors.author(), flowId, draft.sourceSystem(),
                List.of(draft.destination1(), draft.destination2(), destination3));
        UUID newRouteId = LaneCLifecycle.routeIdOfLiveRow(flowId, 3);
        // T-190: the sentinel arrives as the route 3 SAVES - the expression is legitimate content that mints its
        // version row, so the snapshot passes the pin check; the sentinel stays as the live row's content and the
        // publish's mapping write takes the UPDATE path, where the trigger still fails mid-bundle.
        LaneCLifecycle.bindRouteMapping(base, actors.author(), E3ConfigDatabase.tenantA().id(), flowId,
                newRouteId, sentinel);
        LaneCLifecycle.saveFlowDraft(base, actors.author(), flowId, draft.sourceSystem(),
                List.of(draft.destination1(), draft.destination2(), destination3));
        String snapshotV3 = LaneCLifecycle.snapshotText(LaneCLifecycle.latestVersionId(flowId));
        assertThat(snapshotV3).as("the new draft's snapshot carries the sentinel (the premise)")
                .contains(sentinel);
        // The live rows stand as the bound bundle put them - the failing publish may not move them at all.
        String liveBeforePublish = LaneCLifecycle.bundleOfLiveRows(flowId).toString();

        LaneCLifecycle.submit(base, actors, flowId, "T-021: the bundle that will fail");
        Response approved = LaneCLifecycle.approve(base, actors.approver(), flowId);
        assertThat(approved.status()).as("the new version is approved before the failing publish").isEqualTo(200);

        installFailingMappingTrigger(sentinel);
        try {
            Response failedPublish = ConfigAdminApi.call("POST",
                    base + ConfigAdminApi.FLOWS + "/" + flowId + "/publish", actors.approver(), "{}");
            assertThat(failedPublish.status())
                    .as("the publish whose mapping write fails mid-bundle answers a server error, body: %s",
                            failedPublish.body())
                    .isGreaterThanOrEqualTo(500);
        } finally {
            removeFailingMappingTrigger();
        }

        // None of the three parts changed: the interface pointer and status, the route/mapping rows and the
        // version statuses all stand where the last successful publish left them.
        JsonNode interfaceRow = LaneCLifecycle.interfaceRow(flowId);
        assertThat(interfaceRow.path("published_version").asString())
                .as("the failed publish moved no interface pointer")
                .isEqualTo("2");
        assertThat(interfaceRow.path("status").asString())
                .as("the interface stayed published - the previous version stays live")
                .isEqualTo("PUBLISHED");
        assertThat(LaneCLifecycle.countRows("route", flowId))
                .as("the routes stand where the last publish left them")
                .isEqualTo(3);
        assertThat(LaneCLifecycle.countRows("mapping", flowId))
                .as("no half-written mapping row is added or dropped")
                .isEqualTo(3);
        assertThat(LaneCLifecycle.bundleOfLiveRows(flowId).toString())
                .as("the failed publish changed no live row - the bundle is the pre-publish state byte for byte")
                .isEqualTo(liveBeforePublish);
        assertThat(LaneCLifecycle.versionStatus(flowId, 2)).as("the bound version was not archived")
                .isEqualTo("PUBLISHED");
        assertThat(LaneCLifecycle.versionStatus(flowId, 3)).as("the superseded draft is untouched")
                .isEqualTo("DRAFT");
        assertThat(LaneCLifecycle.versionStatus(flowId, 4)).as("the sentinel version stands approved")
                .isEqualTo("APPROVED");

        // And the same approved version publishes cleanly once the injected failure is gone - the data plane
        // moves from the old bundle to the new one, never to nothing.
        LaneCLifecycle.publish(base, actors.approver(), flowId);
        assertThat(LaneCLifecycle.interfaceRow(flowId).path("published_version").asString())
                .as("the whole retry of the publish lands the sentinel version")
                .isEqualTo("4");
        assertThat(LaneCLifecycle.bundleOfLiveRows(flowId))
                .as("and the live bundle equals the snapshot again")
                .isEqualTo(LaneCLifecycle.bundleOfSnapshot(snapshotV3));
        // The retried publish lands the snapshot's own rows: the live bundle equals the approved snapshot
        // byte for byte, failures rolled back, retries exact.
        assertThat(LaneCLifecycle.countRows("route", flowId)).as("the third route landed on the clean retry")
                .isEqualTo(3);
        assertThat(LaneCLifecycle.bundleOfLiveRows(flowId).toString()).contains(sentinel);
        assertThat(LaneCLifecycle.versionStatus(flowId, 2)).as("the superseded published version archived at last")
                .isEqualTo("ARCHIVED");
    }

    // ------------------------------------------------------------------ the injected failure (real database, not a mock)

    /**
     * A BEFORE INSERT OR UPDATE guard on {@code public.mapping}, owned by the migration user: it raises exactly
     * when the sentinel expression arrives, simulating the write that dies in the middle of the bundle. The
     * statement is built from compile-time-safe pieces (the sentinel is a test-generated token, embedded as a
     * server-side string literal - {@code quote_literal} would wrap it in quotes and the comparison would then
     * never match; this is the owner-side fixture path, not an application SQL site).
     */
    private static void installFailingMappingTrigger(String sentinel) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection(); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE OR REPLACE FUNCTION public.t021_fail_mapping_write() RETURNS trigger "
                    + "LANGUAGE plpgsql AS $f$ BEGIN "
                    + "IF NEW.compiled_expression = " + quoteLiteral(sentinel) + " THEN "
                    + "RAISE EXCEPTION 'injected T-021 publish failure: the mapping write dies mid-bundle'; "
                    + "END IF; RETURN NEW; END $f$");
            ddl.execute("DROP TRIGGER IF EXISTS t021_fail_mapping_write ON public.mapping");
            ddl.execute("CREATE TRIGGER t021_fail_mapping_write BEFORE INSERT OR UPDATE ON public.mapping "
                    + "FOR EACH ROW EXECUTE FUNCTION public.t021_fail_mapping_write()");
        }
    }

    private static void removeFailingMappingTrigger() throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection(); Statement ddl = owner.createStatement()) {
            ddl.execute("DROP TRIGGER IF EXISTS t021_fail_mapping_write ON public.mapping");
            ddl.execute("DROP FUNCTION IF EXISTS public.t021_fail_mapping_write()");
        }
    }

    /**
     * The sentinel embedded as a function-body string literal. It is test-generated and carries no quote (asserted
     * here), which is what makes this embedding safe - the fixture never takes user input.
     */
    private static String quoteLiteral(String value) {
        assertThat(value).as("the injected sentinel carries no quote").doesNotContain("'");
        return "'" + value + "'";
    }
}
