package io.integrationplatform.config.e5.system;

import static io.integrationplatform.config.e5.system.E5SystemFixture.SYSTEMS;
import static io.integrationplatform.config.e5.system.E5SystemFixture.call;
import static io.integrationplatform.config.e5.system.E5SystemFixture.randomCode;
import static io.integrationplatform.config.e5.system.E5SystemFixture.scalar;
import static io.integrationplatform.config.e5.system.E5SystemFixture.systemBody;
import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e5.system.E5SystemFixture.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * QA gap (base suite RUN 5): an API OUTBOUND system's delivery URL is {@code target_endpoint.base_url}, which the create
 * wrote once as a placeholder and nothing ever updated. Saving the system's {@code settings.baseUrl} through the real
 * API must keep that column in step; a refused address must change nothing; INBOUND and untyped systems are untouched.
 */
@Tag("container")
@SpringBootTest(classes = E5SystemSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "platform.config.destinations.allowed-hosts=*.e5.test,*.partner.example")
class ApiEndpointBaseUrlContainerTest {

    private static final String PLACEHOLDER = "https://endpoint.invalid/";
    private static final String BASE_SQL = "SELECT string_agg(base_url, ',') FROM public.target_endpoint"
            + " WHERE system_id = ?::uuid";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        E5SystemFixture.wire(registry);
    }

    private static String configuratorOfA() {
        return E5SystemFixture.newUser(E5SystemFixture.tenantA().id(), "CONFIGURATOR");
    }

    private static String api(String baseUrl) {
        return "{\"baseUrl\":\"" + baseUrl + "\",\"timeoutMs\":3000}";
    }

    @Test
    @DisplayName("create: an OUTBOUND API system's default endpoint takes settings.baseUrl; an update follows; a refused one changes nothing")
    void theEndpointFollowsTheSettingsBaseUrl() {
        String subject = configuratorOfA();
        String code = randomCode("sys-api");

        Response created = call(port, "POST", SYSTEMS, subject,
                systemBody(code, "OUTBOUND", "API", api("https://one.partner.example/v1/orders")));
        assertThat(created.status()).as("body: %s", created.body()).isEqualTo(201);
        String id = created.json().path("id").asString();
        assertThat(scalar(BASE_SQL, id)).isEqualTo("https://one.partner.example/v1/orders");

        Response updated = call(port, "PUT", SYSTEMS + "/" + id, subject,
                systemBody(code, "OUTBOUND", "API", api("https://two.partner.example/v2/orders")));
        assertThat(updated.status()).as("body: %s", updated.body()).isEqualTo(200);
        assertThat(scalar(BASE_SQL, id)).isEqualTo("https://two.partner.example/v2/orders");

        Response refused = call(port, "PUT", SYSTEMS + "/" + id, subject,
                systemBody(code, "OUTBOUND", "API", api("https://evil.example/steal")));
        assertThat(refused.status()).as("body: %s", refused.body()).isEqualTo(400);
        assertThat(scalar(BASE_SQL, id)).as("a refused address leaves the old one")
                .isEqualTo("https://two.partner.example/v2/orders");
    }

    @Test
    @DisplayName("a refused baseUrl at create registers nothing; INBOUND has no endpoint; an untyped OUTBOUND keeps the placeholder")
    void otherShapesAreUntouched() {
        String subject = configuratorOfA();
        String refusedCode = randomCode("sys-api");
        Response refused = call(port, "POST", SYSTEMS, subject,
                systemBody(refusedCode, "OUTBOUND", "API", api("https://evil.example/steal")));
        assertThat(refused.status()).as("body: %s", refused.body()).isEqualTo(400);
        assertThat(scalar("SELECT count(*) FROM public.target_endpoint WHERE code = ?", refusedCode)).isEqualTo("0");

        Response inbound = call(port, "POST", SYSTEMS, subject,
                systemBody(randomCode("sys-in"), "INBOUND", "API", api("https://in.partner.example/hook")));
        assertThat(inbound.status()).as("body: %s", inbound.body()).isEqualTo(201);
        assertThat(scalar(BASE_SQL, inbound.json().path("id").asString())).isNull();

        Response untyped = call(port, "POST", SYSTEMS, subject,
                systemBody(randomCode("sys-un"), "OUTBOUND", null, null));
        assertThat(untyped.status()).as("body: %s", untyped.body()).isEqualTo(201);
        assertThat(scalar(BASE_SQL, untyped.json().path("id").asString())).isEqualTo(PLACEHOLDER);
    }
}
