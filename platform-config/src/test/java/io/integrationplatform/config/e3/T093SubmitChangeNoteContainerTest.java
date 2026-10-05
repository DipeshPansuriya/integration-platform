package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Bearer;
import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.PendingIntegration;
import io.integrationplatform.config.e3.E3ConfigDatabase.RestrictedLogin;
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
 * T-093 (E3-F3-S1): the Submit-for-approval changeNote defect, reproduced against the real API - E3-C8.
 *
 * <pre>
 * Scenario: Submit for approval sends a changeNote body
 *   Given the real API
 *   When the console submits a version with a changeNote body
 *   Then it is accepted (the test was red before the fix)
 * </pre>
 *
 * <p>The earlier defect: the console's Submit sent the changeNote in no body and the real API refused or stored
 * it missing. The reproduction is the call the console makes - real HTTP on the real API (no mock, E3-C6). The
 * changeNote the submit sends is what the approval audit reads later (the ConfigVersion's {@code changeNote},
 * the flow's version history).
 *
 * <p>Tagged {@code container} (R03), the committed RED: today the lifecycle API is lane C's T-019 and has not
 * landed; every missing node is gated by name with its owner.
 */
@Tag("container")
@SpringBootTest(classes = ConfigE3App.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class T093SubmitChangeNoteContainerTest {

    private static final AtomicBoolean SEEDED = new AtomicBoolean();

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
    }

    @BeforeEach
    void bearerToken() {
        bearerOfTenantA = new Bearer().forSubject(E3ConfigDatabase.tenantA().subject());
    }

    @Test
    @DisplayName("Scenario: Submit for approval sends a changeNote body - the submit is accepted")
    void submittingAVersionWithAChangeNoteIsAccepted() {
        String base = ConfigAdminApi.baseUrl(port);
        String system = E3ConfigDatabase.randomCode("src");

        // Given: a draft version the author submits (the draft through the same real API).
        Response systemCreated = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, bearerOfTenantA,
                ConfigAdminApi.createSystem(system, "source system of the submitted draft", "INBOUND"));
        Response saved = ConfigAdminApi.call("POST", base + ConfigAdminApi.CONTRACT_VERSIONS, bearerOfTenantA,
                ConfigAdminApi.saveDraft(system, "{\"type\":\"object\",\"required\":[\"orderId\"],"
                        + "\"properties\":{\"orderId\":{\"type\":\"string\"}}}"));
        if (systemCreated.status() == 404 || saved.status() == 404) {
            throw new PendingIntegration("the systems or draft-save API answered 404", "lane C (T-015)");
        }
        assertThat(saved.status()).as("the draft the scenario submits exists").isEqualTo(200);
        String versionId = saved.json().path("id").asString();
        assertThat(versionId).as("the draft the submit names is the saved draft").isNotBlank();

        // When: the console submits it with the changeNote body.
        Response submitted = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.CONTRACT_VERSIONS + "/" + versionId + "/submit", bearerOfTenantA,
                ConfigAdminApi.submit("E3-T093: the version is ready for approval"));
        assertThat(submitted.status())
                .as("the real API accepts the submit WITH the changeNote body (the earlier defect's regression "
                        + "test), body: %s", submitted.body())
                .isEqualTo(200);

        // And the changeNote the console sent is the one stored (a submit without it is not "accepted").
        Response read = ConfigAdminApi.call("GET",
                base + ConfigAdminApi.CONTRACT_VERSIONS + "/" + versionId, bearerOfTenantA, null);
        assertThat(read.json().path("changeNote").asString())
                .as("the changeNote the submit sent is stored with the submitted version")
                .isEqualTo("E3-T093: the version is ready for approval");
    }
}