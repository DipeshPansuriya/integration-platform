package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.PendingIntegration;
import io.integrationplatform.config.e3.E3ConfigDatabase.RestrictedLogin;
import io.integrationplatform.config.e3.E3ConfigDatabase.SchemaSnapshot;
import io.integrationplatform.e3lanec.E3LaneCSuiteApp;
import io.integrationplatform.config.e3.LaneCLifecycle.Actors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
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
 * T-015 (lane C, E3-F1-S1): the flow admin API on a running system - the scenarios lane C owes E3-F1-S1, driving
 * the same contract lane A's T-016 suite pins (that suite is the live acceptance spec; it runs on lane A's own
 * composition and keeps its own RED for lane A's deferred binding choice - this suite proves the API itself through
 * the FULL identity chain on lane C's composition: real bearer, directory tenant binding, per-transaction stamp).
 *
 * <pre>
 * Scenario: Onboard a flow by inserting rows only
 *   Given a running system
 *   When a configurator creates a flow with source system S and destinations T1 and T2 in the console
 *   Then only rows are inserted (0 deploys, 0 migrations, the schema version is unchanged)
 *   And the flow is available to ingest once published
 *
 * Scenario: The interface carries its business-key rule and ordering setting
 *   Given a new interface
 *   When it is saved
 *   Then its mandatory business-key rule and its ordering setting (PER_KEY default or NONE) are stored with it
 *
 * &#64;negative
 * Scenario: A flow without a business-key rule is refused
 *   Given an interface without a business-key rule
 *   When it is saved
 *   Then it is refused with a clear validation error
 *
 * &#64;negative &#64;tenant
 * Scenario: Another tenant's flow is invisible
 *   Given a configurator of tenant B
 *   When they request tenant A's flow by id
 *   Then they get 404 and no row of tenant A is read
 * </pre>
 */
@Tag("container")
@SpringBootTest(classes = E3LaneCSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class T015FlowAdminContainerTest {

    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    /** The suite's one signing key, shared with the composed app's decoder bean (see {@link E3LaneCSuiteApp}). */
    private static final LaneCIdentities TOKENS = E3LaneCSuiteApp.TOKENS;

    private static RestrictedLogin login;
    private static String approverSubject;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        PostgreSQLContainer postgres = E3ConfigDatabase.postgres(); // starts once, on first use
        if (SEEDED.compareAndSet(false, true)) {
            E3ConfigDatabase.seedDirectory(); // named gates first (driver, Flyway, migrations), then the seed
            approverSubject = LaneCIdentities.seedApproverOfTenantA(); // the tenant-A approver (not the author)
        }
        login = E3ConfigDatabase.restrictedLogin();
        LaneCIdentities.assertPremise(login, approverSubject);
        // The fixed environment names of the platform (R07), the way lane A's suites wire them: the app pool
        // connects as the restricted login, Flyway deploys as the migration owner at context start.
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> login.username());
        registry.add("spring.datasource.password", () -> login.password());
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        // The real ADR-007 resource-server chain (the ForgedTenantDenialSuiteTest pattern): only the JWT
        // verification key is the suite's; every other link of the chain is production code.
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

    // ------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("Scenario: Onboard a flow by inserting rows only - 0 deploys, 0 migrations, schema version unchanged")
    void onboardingAFlowInsertsRowsOnly() {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actors = actors();
        String sourceSystem = LaneCIdentities.randomCode("src");
        String destination1 = LaneCIdentities.randomCode("tgt");
        String destination2 = LaneCIdentities.randomCode("tgt");

        SchemaSnapshot before = E3ConfigDatabase.snapshot();

        Response source = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, actors.author(),
                ConfigAdminApi.createSystem(sourceSystem, "source system S", "INBOUND"));
        Response target1 = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, actors.author(),
                ConfigAdminApi.createSystem(destination1, "target T1", "OUTBOUND"));
        Response target2 = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, actors.author(),
                ConfigAdminApi.createSystem(destination2, "target T2", "OUTBOUND"));
        if (source.status() == 404 || target1.status() == 404 || target2.status() == 404) {
            throw new PendingIntegration("the systems admin API answered 404", "lane C (T-015)");
        }
        assertThat(source.status()).as("the systems admin API creates system rows, body: %s", source.body())
                .isEqualTo(201);
        assertThat(target1.status()).isEqualTo(201);
        assertThat(target2.status()).isEqualTo(201);
        assertThat(target1.json().path("code").asString()).as("the OUTBOUND system is registered")
                .isEqualTo(destination1);

        Response created = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS, actors.author(),
                ConfigAdminApi.createFlow(sourceSystem, List.of(destination1, destination2), "$.orderId"));
        if (created.status() == 404) {
            throw new PendingIntegration("the flow admin API answered 404", "lane C (T-015)");
        }
        assertThat(created.status()).as("the flow is created as configuration rows, body: %s", created.body())
                .isEqualTo(201);
        String flowId = created.json().path("id").asString();
        assertThat(flowId).isNotBlank();

        Response read = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + flowId,
                actors.author(), null);
        if (read.status() == 404) {
            throw new PendingIntegration("the flow read API answered 404 for a fresh flow", "lane C (T-015)");
        }
        assertThat(read.status()).as("the created flow is readable, body: %s", read.body()).isEqualTo(200);
        assertThat(read.json().path("sourceSystem").asString()).as("the flow names source system S")
                .isEqualTo(sourceSystem);
        List<String> destinations = new ArrayList<>();
        for (JsonNode destination : read.json().path("destinations")) {
            destinations.add(destination.asString());
        }
        assertThat(destinations).as("the flow carries destinations T1 and T2")
                .containsExactlyInAnyOrder(destination1, destination2);

        // Then: only rows - the schema version is unchanged, 0 migrations ran, 0 deploys (a deploy changes DDL).
        SchemaSnapshot afterFlowCreated = E3ConfigDatabase.snapshot();
        assertThat(afterFlowCreated.appliedVersionRows())
                .as("Flyway's schema history has no new version after the flow is created (0 migrations)")
                .isEqualTo(before.appliedVersionRows());
        assertThat(afterFlowCreated.relations())
                .as("no table was created or removed behind the flow's rows (0 deploys)")
                .isEqualTo(before.relations());

        // And the flow is available to ingest once published: the lifecycle runs, rows only.
        LaneCLifecycle.submitApprovePublish(base, actors, UUID.fromString(flowId), "rows-only onboarding");

        Response published = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + flowId,
                actors.author(), null);
        assertThat(published.json().path("status").asString())
                .as("the published flow's status is what the data plane resolves (available to ingest)")
                .isEqualTo("PUBLISHED");

        assertThat(E3ConfigDatabase.snapshot().appliedVersionRows())
                .as("publishing was rows only too - the schema history is untouched")
                .isEqualTo(before.appliedVersionRows());
    }

    @Test
    @DisplayName("Scenario: The interface carries its business-key rule and ordering setting (ADR-011)")
    void theInterfaceCarriesItsBusinessKeyRuleAndOrderingSetting() {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actors = actors();
        String sourceSystem = LaneCIdentities.randomCode("src");
        String destination = LaneCIdentities.randomCode("tgt");

        LaneCLifecycle.createSystem(base, actors.author(), sourceSystem, "INBOUND");
        LaneCLifecycle.createSystem(base, actors.author(), destination, "OUTBOUND");

        // Saved with both: the rule and an explicit ordering (NONE when a source sequences itself, ADR-011).
        Response saved = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS, actors.author(),
                LaneCLifecycle.flowBody(sourceSystem, List.of(destination), "$.orderId", "NONE"));
        if (saved.status() == 404) {
            throw new PendingIntegration("the flow admin API answered 404", "lane C (T-015)");
        }
        assertThat(saved.status()).as("the flow saves with its rule and ordering, body: %s", saved.body())
                .isEqualTo(201);
        String flowId = saved.json().path("id").asString();

        Response read = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + flowId,
                actors.author(), null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().path("businessKeyRule").asString())
                .as("the mandatory business-key rule is stored with the interface")
                .isEqualTo("$.orderId");
        assertThat(read.json().path("ordering").asString())
                .as("the ordering the flow was saved with (NONE) is stored with the interface")
                .isEqualTo("NONE");

        // A flow saved without an ordering setting carries PER_KEY - the ADR-011 default.
        Response defaultOrdering = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS, actors.author(),
                LaneCLifecycle.flowBody(sourceSystem, List.of(destination), "$.customerRef", null));
        assertThat(defaultOrdering.status()).as("a second flow from the same source is allowed, body: %s",
                defaultOrdering.body()).isEqualTo(201);
        String secondFlowId = defaultOrdering.json().path("id").asString();
        Response readDefault = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + secondFlowId,
                actors.author(), null);
        assertThat(readDefault.json().path("ordering").asString())
                .as("with no setting the ordering is PER_KEY (ADR-011 default)")
                .isEqualTo("PER_KEY");
        assertThat(readDefault.json().path("businessKeyRule").asString())
                .as("each interface keeps its own rule")
                .isEqualTo("$.customerRef");

        // An ordering outside the closed set is refused with a clear validation error (ADR-011 Decision 6).
        Response refused = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS, actors.author(),
                LaneCLifecycle.flowBody(sourceSystem, List.of(destination), "$.orderId", "BY_DATE"));
        assertThat(refused.status()).as("an unknown ordering is refused, body: %s", refused.body())
                .isEqualTo(400);
    }

    @Test
    @Tag("negative")
    @DisplayName("@negative Scenario: A flow without a business-key rule is refused with a clear validation error")
    void aFlowWithoutBusinessKeyRuleIsRefused() {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actors = actors();
        String sourceSystem = LaneCIdentities.randomCode("src");
        String destination = LaneCIdentities.randomCode("tgt");

        Response source = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, actors.author(),
                ConfigAdminApi.createSystem(sourceSystem, "source system S", "INBOUND"));
        if (source.status() == 404) {
            throw new PendingIntegration("the systems admin API answered 404", "lane C (T-015)");
        }
        assertThat(source.status()).isEqualTo(201);
        ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, actors.author(),
                ConfigAdminApi.createSystem(destination, "target T1", "OUTBOUND"));

        Response created = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS, actors.author(),
                ConfigAdminApi.createFlowWithoutBusinessKeyRule(sourceSystem, List.of(destination)));
        if (created.status() == 404) {
            throw new PendingIntegration("the flow admin API (create flow) answered 404", "lane C (T-015)");
        }
        assertThat(created.status()).as("an interface without its mandatory business-key rule is refused, body: %s",
                created.body()).isEqualTo(400);
        assertThat(created.body().toLowerCase()).as("the refusal is a clear validation error, naming the missing rule")
                .contains("business").contains("key");

        // And nothing was written by the refused save: the source system exists, the flow does not.
        Response flows = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS, actors.author(), null);
        assertThat(flows.status()).as("the flow collection answers, body: %s", flows.body()).isEqualTo(200);
        assertThat(flows.json().toString()).as("the refused flow was not stored")
                .doesNotContain(sourceSystem);
    }

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("@negative @tenant Scenario: Another tenant's flow is invisible - 404, no tenant A row read")
    void anotherTenantsFlowIsInvisible() {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actors = actors();
        String sourceSystem = LaneCIdentities.randomCode("src");

        Response system = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, actors.author(),
                ConfigAdminApi.createSystem(sourceSystem, "tenant A's system", "INBOUND"));
        Response flow = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS, actors.author(),
                ConfigAdminApi.createFlow(sourceSystem, List.of(LaneCIdentities.randomCode("tgt")), "$.orderId"));
        if (system.status() == 404 || flow.status() == 404) {
            throw new PendingIntegration("the admin API (systems or flows) answered 404", "lane C (T-015)");
        }
        assertThat(system.status()).isEqualTo(201);
        assertThat(flow.status()).as("tenant A's configurator created the flow").isEqualTo(201);
        String flowId = flow.json().path("id").asString();
        assertThat(flowId).as("a flow id the invisible-read names").isNotBlank();

        Response asA = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + flowId,
                actors.author(), null);
        assertThat(asA.status()).as("the premise holds: tenant A reads its own flow").isEqualTo(200);

        // Then: a configurator of a different tenant gets 404, nothing of tenant A in the answer.
        Response asOther = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + flowId,
                actors.otherTenantUser(), null);
        assertThat(asOther.status()).as("the other tenant must not see the flow by id").isEqualTo(404);
        E3ConfigDatabase.SeededTenant a = E3ConfigDatabase.tenantA();
        assertThat(asOther.body()).as("no row of tenant A is read - no identifier of tenant A is even in the answer")
                .doesNotContain(a.id().toString(), a.code(), a.userId().toString(), sourceSystem);

        // And the same for the write paths: tenant B cannot submit, approve or publish tenant A's flow either.
        Response asOtherSubmit = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.FLOWS + "/" + flowId + "/submit", actors.otherTenantUser(),
                ConfigAdminApi.submit("cross-tenant attempt"));
        assertThat(asOtherSubmit.status()).as("the cross-tenant submit finds no draft").isEqualTo(404);
        E3ConfigDatabase.SeededTenant b = E3ConfigDatabase.tenantB();
        assertThat(asOtherSubmit.body()).as("no row of tenant A is read by the refused write")
                .doesNotContain(a.id().toString(), sourceSystem);
        assertThat(b).as("premise: the other tenant is its own seeded tenant").isNotNull();
    }
}
