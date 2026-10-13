package io.integrationplatform.config.e3;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.PendingIntegration;
import io.integrationplatform.config.e3.E3ConfigDatabase.RestrictedLogin;
import io.integrationplatform.config.e3.E3ConfigDatabase.SchemaSnapshot;
import io.integrationplatform.config.e3.E3ConfigDatabase.SeededTenant;
import io.integrationplatform.e3lanea.E3LaneASuiteApp;
import java.util.ArrayList;
import java.util.List;
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
 * T-016 (E3-F1-S1): rows-only onboarding on a running system - the E3-C2 proof.
 *
 * <pre>
 * Scenario: Onboard a flow by inserting rows only
 *   Given a running system
 *   When a configurator creates a flow with source system S and destinations T1 and T2 in the console
 *         (through the real admin API)
 *   Then only rows are inserted (0 deploys, 0 migrations, the schema version is unchanged) - assert Flyway's
 *         schema history has no new version after the flow is created
 *   And the flow is available to ingest once published
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
 *
 * <p>Tagged {@code container} (R03): runs only under {@code ./mvnw -Pcontainer}, one Docker suite at a time (R06).
 * The system under test is composed from test configuration ({@link E3LaneASuiteApp}) and served on a real port;
 * every scenario drives the real admin API (no mock - E3-C6).
 *
 * <p>Written test-first (the committed RED of the red-first lane); E3 fix round 1 binds its composition to the
 * real ADR-007 identity chain (lane C's proven pattern). The flow admin and lifecycle APIs (lane C's T-015/T-019)
 * have landed since; the lifecycle walk therefore needs "another user" as ADR-007 Decision 2 means it - an
 * approver of the author's own tenant, seeded beside lane A's own directory seed
 * ({@link LaneCIdentities#seedApproverOfTenantA()}), never a user of another tenant.
 */
@Tag("container")
@SpringBootTest(classes = E3LaneASuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class T016RowsOnlyOnboardingContainerTest {

    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    /** The suite's one signing key, shared with the composed app's decoder bean (see {@link E3LaneASuiteApp}). */
    private static final LaneCIdentities TOKENS = E3LaneASuiteApp.TOKENS;

    private static RestrictedLogin login;
    private static String approverSubject;
    private static String bearerOfTenantA;
    private static String bearerOfApprover;
    private static String bearerOfTenantB;

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
        // The fixed environment names of the platform (R07) - the composed context maps them like platform-app's
        // E1 suites do: the app pool connects as the restricted login, Flyway (when armed) as the migration owner.
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> login.username());
        registry.add("spring.datasource.password", () -> login.password());
        registerFlywayDeployment(registry);
        // The real ADR-007 resource-server chain (the ForgedTenantDenialSuiteTest pattern): only the JWT
        // verification key is the suite's (E3LaneASuiteApp's decoder), every other link is production code.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> E3ConfigDatabase.OIDC_ISSUER.toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences",
                () -> E3ConfigDatabase.OIDC_AUDIENCE);
        registry.add("spring.security.oauth2.resourceserver.jwt.jws-algorithms", () -> "RS256");
    }

    private static void registerFlywayDeployment(DynamicPropertyRegistry registry) {
        // When the lead arms the pom (starter-flyway), the composed context deploys the config module's own
        // migrations at start as the migration login - the way platform-app's E1 suites let it run.
        PostgreSQLContainer postgres = E3ConfigDatabase.postgres();
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @BeforeEach
    void bearerTokens() {
        SeededTenant a = E3ConfigDatabase.tenantA();
        SeededTenant b = E3ConfigDatabase.tenantB();
        bearerOfTenantA = TOKENS.accessToken(a.subject());
        bearerOfApprover = TOKENS.accessToken(approverSubject); // same tenant, different user (ADR-007 Decision 2)
        bearerOfTenantB = TOKENS.accessToken(b.subject());
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("Scenario: Onboard a flow by inserting rows only - 0 deploys, 0 migrations, schema version unchanged")
    void onboardingAFlowInsertsRowsOnly() {
        String base = ConfigAdminApi.baseUrl(port);
        String sourceSystem = E3ConfigDatabase.randomCode("src");
        String destination1 = E3ConfigDatabase.randomCode("tgt");
        String destination2 = E3ConfigDatabase.randomCode("tgt");

        SchemaSnapshot before = E3ConfigDatabase.snapshot();

        // The configurator creates the systems the flow names, in the console: real rows, through the real API.
        Response source = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, bearerOfTenantA,
                ConfigAdminApi.createSystem(sourceSystem, "source system S", "INBOUND"));
        Response target1 = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, bearerOfTenantA,
                ConfigAdminApi.createSystem(destination1, "target T1", "OUTBOUND"));
        Response target2 = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, bearerOfTenantA,
                ConfigAdminApi.createSystem(destination2, "target T2", "OUTBOUND"));
        assertThat(source.status()).as("the systems admin API exists (owner lane C, T-015), body: %s", source.body())
                .isEqualTo(201);
        assertThat(target1.status()).as("the systems admin API exists (owner lane C, T-015)").isEqualTo(201);
        assertThat(target2.status()).as("the systems admin API exists (owner lane C, T-015)").isEqualTo(201);

        // The flow: source S, destinations T1 and T2.
        Response created = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS, bearerOfTenantA,
                ConfigAdminApi.createFlow(sourceSystem, List.of(destination1, destination2), "$.orderId"));
        assertThat(created.status())
                .as("the flow admin API exists (owner lane C, T-015) - the console creates the flow through the "
                        + "real API, body: %s", created.body())
                .isEqualTo(201);
        String flowId = created.json().path("id").asString();

        Response read = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + flowId, bearerOfTenantA, null);
        assertThat(read.status()).as("the flow the API created is readable (rows exist), body: %s", read.body())
                .isEqualTo(200);
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

        // And the flow is available to ingest once published: the real lifecycle runs, rows only. The approver is
        // a second user of tenant A ("another user", ADR-007 Decision 2) - never a user of another tenant.
        // T-190: the mapping binds to contract versions and the bundle is re-captured before the lifecycle.
        LaneCLifecycle.bindAllRoutesAndResnapshot(base, bearerOfTenantA, E3ConfigDatabase.tenantA().id(),
                UUID.fromString(flowId));
        Response submit = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS + "/" + flowId + "/submit",
                bearerOfTenantA, ConfigAdminApi.submit("rows-only onboarding"));
        Response approve = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS + "/" + flowId + "/approve",
                bearerOfApprover, "{}");
        Response publish = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS + "/" + flowId + "/publish",
                bearerOfApprover, "{}");
        assertThat(submit.status())
                .as("the flow lifecycle exists (owner lane C, T-019) - submit, body: %s", submit.body())
                .isEqualTo(200);
        assertThat(approve.status())
                .as("the flow lifecycle exists (owner lane C, T-019) - approve by another user, body: %s",
                        approve.body())
                .isEqualTo(200);
        assertThat(publish.status())
                .as("the flow lifecycle exists (owner lane C, T-019) - publish, body: %s", publish.body())
                .isEqualTo(200);

        Response published = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + flowId,
                bearerOfTenantA, null);
        assertThat(published.json().path("status").asString())
                .as("the published flow's status is what the data plane resolves (available to ingest)")
                .isEqualTo("PUBLISHED");

        assertThat(E3ConfigDatabase.snapshot().appliedVersionRows())
                .as("publishing was rows only too - the schema history is untouched")
                .isEqualTo(before.appliedVersionRows());
    }

    @Test
    @Tag("negative")
    @DisplayName("@negative Scenario: A flow without a business-key rule is refused with a clear validation error")
    void aFlowWithoutBusinessKeyRuleIsRefused() {
        String base = ConfigAdminApi.baseUrl(port);
        String sourceSystem = E3ConfigDatabase.randomCode("src");
        String destination = E3ConfigDatabase.randomCode("tgt");

        Response source = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, bearerOfTenantA,
                ConfigAdminApi.createSystem(sourceSystem, "source system S", "INBOUND"));
        if (source.status() == 404) { // the pending integration names itself instead of failing vaguely
            throw new PendingIntegration("the systems admin API (create system S) answered 404", "lane C (T-015)");
        }
        assertThat(source.status()).isEqualTo(201);
        ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, bearerOfTenantA,
                ConfigAdminApi.createSystem(destination, "target T1", "OUTBOUND"));

        Response created = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS, bearerOfTenantA,
                ConfigAdminApi.createFlowWithoutBusinessKeyRule(sourceSystem, List.of(destination)));
        if (created.status() == 404) {
            throw new PendingIntegration("the flow admin API (create flow) answered 404", "lane C (T-015)");
        }
        assertThat(created.status()).as("an interface without its mandatory business-key rule is refused, body: %s",
                created.body()).isEqualTo(400);
        assertThat(created.body().toLowerCase()).as("the refusal is a clear validation error, naming the missing rule")
                .contains("business").contains("key");
    }

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("@negative @tenant Scenario: Another tenant's flow is invisible - 404, no tenant A row read")
    void anotherTenantsFlowIsInvisible() {
        String base = ConfigAdminApi.baseUrl(port);
        String sourceSystem = E3ConfigDatabase.randomCode("src");

        Response system = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, bearerOfTenantA,
                ConfigAdminApi.createSystem(sourceSystem, "tenant A's system", "INBOUND"));
        Response flow = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS, bearerOfTenantA,
                ConfigAdminApi.createFlow(sourceSystem,
                        List.of(E3ConfigDatabase.randomCode("tgt")), "$.orderId"));
        if (system.status() == 404 || flow.status() == 404) {
            throw new PendingIntegration("the admin API (systems or flows) answered 404", "lane C (T-015)");
        }
        assertThat(system.status()).isEqualTo(201);
        assertThat(flow.status()).as("tenant A's configurator created the flow").isEqualTo(201);
        String flowId = flow.json().path("id").asString();
        assertThat(flowId).as("a flow id the invisible-read names").isNotBlank();

        Response asA = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + flowId, bearerOfTenantA, null);
        assertThat(asA.status()).as("the premise holds: tenant A reads its own flow").isEqualTo(200);

        // Then: a configurator of tenant B gets 404, and nothing of tenant A is in the answer.
        Response asB = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + flowId, bearerOfTenantB, null);
        assertThat(asB.status()).as("tenant B must not see tenant A's flow by id").isEqualTo(404);
        SeededTenant a = E3ConfigDatabase.tenantA();
        assertThat(asB.body()).as("no row of tenant A is read - no identifier of tenant A is even in the answer")
                .doesNotContain(a.id().toString(), a.code(), a.userId().toString(), sourceSystem, "alpha");
    }
}