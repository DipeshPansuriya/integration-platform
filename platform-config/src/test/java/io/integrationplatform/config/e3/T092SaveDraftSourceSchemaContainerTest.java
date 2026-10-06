package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.PendingIntegration;
import io.integrationplatform.config.e3.E3ConfigDatabase.RestrictedLogin;
import io.integrationplatform.e3lanea.E3LaneASuiteApp;
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

/**
 * T-092 (E3-F3-S1): the Save-draft sourceSchema defect, reproduced against the real API - the E3-C6/E3-C7 proof.
 *
 * <pre>
 * Scenario: Save draft sends the required sourceSchema
 *   Given the real API (not a mock)
 *   When the console saves a draft with its sourceSchema
 *   Then the API answers 200 (the test was red before the fix)
 *
 * &#64;negative
 * Scenario: Save draft without sourceSchema is a clear 400
 *   Given a draft without the required sourceSchema
 *   When it is saved through the API
 *   Then the answer is 400 naming the missing field
 * </pre>
 *
 * <p>The earlier defect: the console's Save draft dropped the {@code sourceSchema} request field and got a 400
 * from the real API (the ADR-011 Decision 2 draft-save API keeps that field on the request and stores it as a
 * draft source contract version). The reproduction is the call the console makes - real HTTP, real port, no mock
 * - not the console's mock wiring.
 *
 * <p>Tagged {@code container} (R03), written test-first like {@link T016RowsOnlyOnboardingContainerTest}; the
 * draft-save API (lane C's T-015) has landed since, and E3 fix round 1 bound this suite's composition to the
 * real ADR-007 identity chain ({@link E3LaneASuiteApp} - lane C's proven pattern, the suite's decoder on the key
 * that mints these bearers).
 */
@Tag("container")
@SpringBootTest(classes = E3LaneASuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class T092SaveDraftSourceSchemaContainerTest {

    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    /** The suite's one signing key, shared with the composed app's decoder bean (see {@link E3LaneASuiteApp}). */
    private static final LaneCIdentities TOKENS = E3LaneASuiteApp.TOKENS;

    private static RestrictedLogin login;
    private static String bearerOfTenantA;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        PostgreSQLContainer postgres = E3ConfigDatabase.postgres();
        if (SEEDED.compareAndSet(false, true)) {
            E3ConfigDatabase.seedDirectory(); // named gates: driver, Flyway, migrations, tenants
        }
        login = E3ConfigDatabase.restrictedLogin();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> login.username());
        registry.add("spring.datasource.password", () -> login.password());
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        // The real ADR-007 resource-server chain (the ForgedTenantDenialSuiteTest pattern): only the JWT
        // verification key is the suite's (E3LaneASuiteApp's decoder), every other link is production code.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> E3ConfigDatabase.OIDC_ISSUER.toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences",
                () -> E3ConfigDatabase.OIDC_AUDIENCE);
        registry.add("spring.security.oauth2.resourceserver.jwt.jws-algorithms", () -> "RS256");
    }

    @BeforeEach
    void bearerToken() {
        bearerOfTenantA = TOKENS.accessToken(E3ConfigDatabase.tenantA().subject());
    }

    /**
     * The minimal JSON Schema the console's Save draft sends (a real draft source schema, draft 2020-12).
     */
    private static String sourceSchema() {
        return """
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "type":"object",
                 "required":["orderId"],
                 "properties":{"orderId":{"type":"string"},"qty":{"type":"integer"}}}""";
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("Scenario: Save draft sends the required sourceSchema - the real API answers 200")
    void savingADraftWithSourceSchemaAnswers200() {
        String base = ConfigAdminApi.baseUrl(port);
        String system = E3ConfigDatabase.randomCode("src");

        Response systemCreated = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, bearerOfTenantA,
                ConfigAdminApi.createSystem(system, "source system for the draft", "INBOUND"));
        if (systemCreated.status() == 404) {
            throw new PendingIntegration("the systems admin API answered 404", "lane C (T-015)");
        }
        assertThat(systemCreated.status()).as("the source system the draft names exists (rows only)").isEqualTo(201);

        Response saved = ConfigAdminApi.call("POST", base + ConfigAdminApi.CONTRACT_VERSIONS, bearerOfTenantA,
                ConfigAdminApi.saveDraft(system, sourceSchema()));
        assertThat(saved.status())
                .as("the real API answers 200 for a draft sent WITH its sourceSchema (the earlier defect's "
                        + "regression test), body: %s", saved.body())
                .isEqualTo(200);

        // And the schema the console sent is the schema the draft holds (the field was really stored, not 200'd).
        Response read = ConfigAdminApi.call("GET", base + ConfigAdminApi.CONTRACT_VERSIONS + "/"
                + saved.json().path("id").asString(), bearerOfTenantA, null);
        assertThat(read.status()).as("the saved draft is readable").isEqualTo(200);
        assertThat(read.json().path("sourceSchema").toString())
                .as("the draft holds the sourceSchema the save sent")
                .contains("orderId");
    }

    @Test
    @Tag("negative")
    @DisplayName("@negative Scenario: Save draft without sourceSchema is a clear 400 naming the missing field")
    void savingADraftWithoutSourceSchemaIsAClear400() {
        String base = ConfigAdminApi.baseUrl(port);
        String system = E3ConfigDatabase.randomCode("src");

        ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, bearerOfTenantA,
                ConfigAdminApi.createSystem(system, "source system for the refusals", "INBOUND"));

        Response saved = ConfigAdminApi.call("POST", base + ConfigAdminApi.CONTRACT_VERSIONS, bearerOfTenantA,
                ConfigAdminApi.saveDraftMissingSourceSchema(system));
        if (saved.status() == 404) {
            throw new PendingIntegration("the draft-save API answered 404", "lane C (T-015)");
        }
        assertThat(saved.status())
                .as("a draft without the required sourceSchema is refused, body: %s", saved.body())
                .isEqualTo(400);
        assertThat(saved.body()).as("the refusal names the missing field")
                .contains("sourceSchema");
    }
}