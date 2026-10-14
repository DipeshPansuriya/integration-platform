package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.RestrictedLogin;
import io.integrationplatform.config.e3.LaneCLifecycle.Actors;
import io.integrationplatform.config.e3.LaneCLifecycle.Draft;
import io.integrationplatform.e3lanec.E3LaneCSuiteApp;
import java.util.ArrayList;
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
 * T81 / B9 (row 278 wave 2): the routes-listing API the flow designer navigates by, on lane C's composed running
 * system (the full identity chain: real bearer, directory tenant binding, per-transaction stamp).
 *
 * <pre>
 * Scenario: The designer lists a flow's routes
 *   Given a draft flow with destinations T1 and T2
 *   When a configurator lists the flow's routes
 *   Then they get the current version's two routes in sequence order, ids equal to the live route rows,
 *        each named by its target endpoint, with the target system and the mapping the designer opens
 *
 * Scenario: The routes of a version named by id
 *   When the configurator names the flow's version by id
 *   Then the routes of that version are listed
 *
 * &#64;negative &#64;tenant
 * Scenario: Another tenant's flow, an unknown flow or a foreign version answers 404
 * </pre>
 */
@Tag("container")
@SpringBootTest(classes = E3LaneCSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class T081FlowRoutesListingContainerTest {

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

    private String routesUrl(UUID flowId) {
        return ConfigAdminApi.baseUrl(port) + ConfigAdminApi.FLOWS + "/" + flowId + "/routes";
    }

    @Test
    @DisplayName("Scenario: The designer lists a flow's routes - current version, sequence order, named, mapped")
    void theDesignerListsAFlowsRoutes() throws Exception {
        Actors actors = actors();
        Draft draft = LaneCLifecycle.createDraftFlowWithTwoDestinations(ConfigAdminApi.baseUrl(port), actors);

        Response listed = ConfigAdminApi.call("GET", routesUrl(draft.flowId()), actors.author(), null);

        assertThat(listed.status()).as("the routes listing answers, body: %s", listed.body()).isEqualTo(200);
        JsonNode json = listed.json();
        assertThat(json.path("flowId").asString()).isEqualTo(draft.flowId().toString());
        assertThat(json.path("versionId").asString()).as("the current version is the latest one")
                .isEqualTo(LaneCLifecycle.latestVersionId(draft.flowId()).toString());
        assertThat(json.path("versionStatus").asString()).isEqualTo("DRAFT");

        List<String> ids = new ArrayList<>();
        List<String> systems = new ArrayList<>();
        for (JsonNode route : json.path("routes")) {
            ids.add(route.path("id").asString());
            systems.add(route.path("targetSystem").asString());
            assertThat(route.path("name").asString()).as("every route carries a name").isNotBlank();
            assertThat(route.path("mappingId").asString()).as("every route carries the mapping the designer opens")
                    .isNotBlank();
            assertThat(route.path("targetEndpointId").asString()).isNotBlank();
        }
        assertThat(ids).as("the route ids are the live route rows, in sequence order").containsExactly(
                LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1).toString(),
                LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 2).toString());
        assertThat(systems).as("each route names its destination system").containsExactly(
                draft.destination1(), draft.destination2());
    }

    @Test
    @DisplayName("Scenario: The routes of a version named by id")
    void theRoutesOfANamedVersion() throws Exception {
        Actors actors = actors();
        Draft draft = LaneCLifecycle.createDraftFlowWithTwoDestinations(ConfigAdminApi.baseUrl(port), actors);
        UUID versionId = LaneCLifecycle.latestVersionId(draft.flowId());

        Response listed = ConfigAdminApi.call("GET", routesUrl(draft.flowId()) + "?versionId=" + versionId,
                actors.author(), null);

        assertThat(listed.status()).as("body: %s", listed.body()).isEqualTo(200);
        assertThat(listed.json().path("versionId").asString()).isEqualTo(versionId.toString());
        assertThat(listed.json().path("routes").size()).isEqualTo(2);
    }

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("@negative @tenant Scenario: another tenant's flow, an unknown flow or a foreign version answers 404")
    void foreignOrUnknownAnswers404() {
        Actors actors = actors();
        Draft draft = LaneCLifecycle.createDraftFlowWithTwoDestinations(ConfigAdminApi.baseUrl(port), actors);

        Response asOther = ConfigAdminApi.call("GET", routesUrl(draft.flowId()), actors.otherTenantUser(), null);
        assertThat(asOther.status()).as("the other tenant must not list the flow's routes").isEqualTo(404);
        assertThat(asOther.body()).as("nothing of tenant A is in the answer")
                .doesNotContain(E3ConfigDatabase.tenantA().id().toString(), draft.destination1(),
                        draft.destination2(), draft.sourceSystem());

        Response unknown = ConfigAdminApi.call("GET", routesUrl(UUID.randomUUID()), actors.author(), null);
        assertThat(unknown.status()).as("an unknown flow answers 404").isEqualTo(404);

        Response foreignVersion = ConfigAdminApi.call("GET",
                routesUrl(draft.flowId()) + "?versionId=" + UUID.randomUUID(), actors.author(), null);
        assertThat(foreignVersion.status()).as("a version that is not the flow's answers 404").isEqualTo(404);
    }
}
