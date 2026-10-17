package io.integrationplatform.config.e5.system;

import static io.integrationplatform.config.e5.system.E5SystemFixture.SYSTEMS;
import static io.integrationplatform.config.e5.system.E5SystemFixture.call;
import static io.integrationplatform.config.e5.system.E5SystemFixture.randomCode;
import static io.integrationplatform.config.e5.system.E5SystemFixture.scalar;
import static io.integrationplatform.config.e5.system.E5SystemFixture.systemBody;
import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e5.system.E5SystemFixture.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * T-102 (E5-F1-S1, PostgreSQL + the real identity chain): the systems registry on a running system.
 *
 * <pre>
 * Scenario Outline: Register a system of each type
 *   Given the systems screen
 *   When a configurator registers a &lt;type&gt; system with its type-specific settings
 *   Then it is stored in the flow-configuration systems table's extension (no second systems table)
 *   Examples: API, DB, MQ, File
 *
 * &#64;negative
 * Scenario: An unknown type or missing setting is refused
 *   Given a system of type "FTP", or an API system without a base address
 *   When it is saved
 *   Then it is refused with every validation error listed
 *
 * &#64;negative &#64;tenant
 * Scenario: Systems are tenant-scoped
 *   Given a configurator of tenant B
 *   When they list or open systems
 *   Then no system of tenant A is returned
 * </pre>
 *
 * The screens are lane D's (T-159); this suite drives the API the screens call.
 */
@Tag("container")
@SpringBootTest(classes = E5SystemSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "platform.config.destinations.allowed-hosts=*.e5.test,*.partner.example,mq.example")
class SystemRegistryContainerTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        E5SystemFixture.wire(registry);
    }

    private static String configuratorOfA() {
        return E5SystemFixture.newUser(E5SystemFixture.tenantA().id(), "CONFIGURATOR");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "API  | OUTBOUND | {\"baseUrl\":\"https://api.partner.example/v1\",\"timeoutMs\":3000}",
        "DB   | OUTBOUND | {\"host\":\"db.partner.example\",\"port\":5432,\"database\":\"orders\"}",
        "MQ   | OUTBOUND | {\"host\":\"mq.partner.example\",\"port\":1414,\"queue\":\"ORDERS.IN\"}",
        "FILE | OUTBOUND | {\"rootPath\":\"/data/outbound\"}"})
    @DisplayName("Scenario Outline: Register a system of each type - stored in the systems table's extension")
    void registerASystemOfEachType(String type, String direction, String settings) {
        String code = randomCode("sys-" + type.toLowerCase());

        Response created = call(port, "POST", SYSTEMS, configuratorOfA(), systemBody(code, direction, type, settings));

        assertThat(created.status()).as("body: %s", created.body()).isEqualTo(201);
        JsonNode answer = created.json();
        assertThat(answer.path("code").asString()).isEqualTo(code);
        assertThat(answer.path("type").asString()).isEqualTo(type);
        assertThat(answer.path("settings").isObject()).isTrue();
        String id = answer.path("id").asString();
        // stored in public.system itself (the E3 table, extended by type + settings), for tenant A
        assertThat(scalar("SELECT type || '|' || tenant_id || '|' || code FROM public.system WHERE id = ?::uuid", id))
                .isEqualTo(type + "|" + E5SystemFixture.tenantA().id() + "|" + code);
        assertThat(scalar("SELECT settings = ?::jsonb FROM public.system WHERE id = ?::uuid", settings, id))
                .as("the settings are the jsonb the configurator sent").isEqualTo("t");
        // no second systems table: public.system is the only relation named like a systems table
        assertThat(scalar("SELECT string_agg(table_name, ',' ORDER BY table_name) FROM information_schema.tables"
                + " WHERE table_schema = 'public' AND table_name LIKE '%system%'")).isEqualTo("system");

        Response read = call(port, "GET", SYSTEMS + "/" + id, configuratorOfA(), null);
        assertThat(read.status()).as("body: %s", read.body()).isEqualTo(200);
        assertThat(read.json().path("type").asString()).isEqualTo(type);
        assertThat(read.json().path("settings")).isEqualTo(answer.path("settings"));
        assertThat(read.json().path("direction").asString()).isEqualTo(direction);
    }

    @Test
    @Tag("negative")
    @DisplayName("Scenario: An unknown type or missing setting is refused - with every validation error listed")
    void anUnknownTypeOrMissingSettingIsRefused() {
        String subject = configuratorOfA();
        String ftp = randomCode("sys-ftp");

        Response unknownType = call(port, "POST", SYSTEMS, subject,
                systemBody(ftp, "OUTBOUND", "FTP", "{\"host\":\"ftp.partner.example\"}"));

        assertThat(unknownType.status()).as("body: %s", unknownType.body()).isEqualTo(400);
        assertThat(errorsOf(unknownType)).singleElement().asString().contains("type").contains("FTP");
        assertThat(scalar("SELECT count(*) FROM public.system WHERE code = ?", ftp)).isEqualTo("0");

        Response noBase = call(port, "POST", SYSTEMS, subject,
                systemBody(randomCode("sys-api"), "OUTBOUND", "API", "{}"));
        assertThat(noBase.status()).as("body: %s", noBase.body()).isEqualTo(400);
        assertThat(errorsOf(noBase)).singleElement().asString().contains("baseUrl");

        // every error at once: the body's own fields and the settings' fields
        Response many = call(port, "POST", SYSTEMS, subject,
                "{\"name\":\"no code\",\"direction\":\"SIDEWAYS\",\"type\":\"DB\",\"settings\":{\"port\":0}}");
        assertThat(many.status()).as("body: %s", many.body()).isEqualTo(400);
        List<String> errors = errorsOf(many);
        assertThat(String.join("\n", errors)).contains("code").contains("direction").contains("host")
                .contains("port").contains("database");
        assertThat(errors).hasSize(5);
        assertThat(many.json().path("error").asString()).as("the summary names every error too")
                .contains("code").contains("database");
    }

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("Scenario: Systems are tenant-scoped - B lists and opens none of A's systems")
    void systemsAreTenantScoped() {
        String code = randomCode("sys-a-only");
        Response created = call(port, "POST", SYSTEMS, configuratorOfA(),
                systemBody(code, "OUTBOUND", "API", "{\"baseUrl\":\"https://a.partner.example/\"}"));
        assertThat(created.status()).isEqualTo(201);
        String idOfA = created.json().path("id").asString();
        String configuratorOfB = E5SystemFixture.newUser(E5SystemFixture.tenantB().id(), "CONFIGURATOR");

        Response listOfB = call(port, "GET", SYSTEMS, configuratorOfB, null);
        assertThat(listOfB.status()).isEqualTo(200);
        assertThat(listOfB.body()).doesNotContain(idOfA).doesNotContain(code);

        Response openByB = call(port, "GET", SYSTEMS + "/" + idOfA, configuratorOfB, null);
        assertThat(openByB.status()).isEqualTo(404);
        assertThat(openByB.body()).doesNotContain(code);

        Response editByB = call(port, "PUT", SYSTEMS + "/" + idOfA, configuratorOfB,
                "{\"type\":\"API\",\"settings\":{\"baseUrl\":\"https://b.partner.example/\"}}");
        assertThat(editByB.status()).isEqualTo(404);
        assertThat(scalar("SELECT settings->>'baseUrl' FROM public.system WHERE id = ?::uuid", idOfA))
                .isEqualTo("https://a.partner.example/");

        Response listOfA = call(port, "GET", SYSTEMS, configuratorOfA(), null);
        assertThat(listOfA.body()).contains(idOfA).contains(code);
    }

    @Test
    @DisplayName("PUT edits a system's settings (and may name a type for an E3 untyped system)")
    void editSettings() {
        String subject = configuratorOfA();
        Response legacy = call(port, "POST", SYSTEMS, subject, systemBody(randomCode("sys-legacy"), "INBOUND", null,
                null));
        assertThat(legacy.status()).as("the E3 registration without type stays valid, body: %s", legacy.body())
                .isEqualTo(201);
        assertThat(legacy.json().path("type").isNull()).isTrue();
        String id = legacy.json().path("id").asString();

        Response edited = call(port, "PUT", SYSTEMS + "/" + id, subject,
                "{\"name\":\"renamed\",\"type\":\"MQ\",\"settings\":{\"host\":\"mq.example\",\"port\":1414,"
                        + "\"queue\":\"Q1\"}}");

        assertThat(edited.status()).as("body: %s", edited.body()).isEqualTo(200);
        assertThat(edited.json().path("name").asString()).isEqualTo("renamed");
        assertThat(edited.json().path("type").asString()).isEqualTo("MQ");
        assertThat(scalar("SELECT type || '|' || (settings->>'queue') FROM public.system WHERE id = ?::uuid", id))
                .isEqualTo("MQ|Q1");

        Response refused = call(port, "PUT", SYSTEMS + "/" + id, subject,
                "{\"type\":\"MQ\",\"settings\":{\"host\":\"mq.example\"}}");
        assertThat(refused.status()).isEqualTo(400);
        assertThat(errorsOf(refused)).hasSize(2);
        assertThat(scalar("SELECT settings->>'queue' FROM public.system WHERE id = ?::uuid", id))
                .as("a refused edit changes nothing").isEqualTo("Q1");
    }

    @Test
    @Tag("negative")
    @DisplayName("An id that is not a uuid, or no row, answers 404 (never a 500)")
    void unknownIdsAnswer404() {
        assertThat(call(port, "GET", SYSTEMS + "/not-a-uuid", configuratorOfA(), null).status()).isEqualTo(404);
        assertThat(call(port, "GET", SYSTEMS + "/" + UUID.randomUUID(), configuratorOfA(), null).status())
                .isEqualTo(404);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A READ_ONLY user lists and opens systems but cannot register or edit one")
    void readOnlyReadsButNeverWrites() {
        String reader = E5SystemFixture.newUser(E5SystemFixture.tenantA().id(), "READ_ONLY");
        assertThat(call(port, "GET", SYSTEMS, reader, null).status()).isEqualTo(200);
        assertThat(call(port, "POST", SYSTEMS, reader, systemBody(randomCode("sys-ro"), "INBOUND", null, null))
                .status()).isEqualTo(403);
    }

    @Test
    @Tag("negative")
    @DisplayName("T-184: the one destination guard refuses a host that can never be an outbound destination - "
            + "at save time, before any DNS query")
    void saveTimeDestinationGuardRefuses() {
        String subject = configuratorOfA();

        Response privateApi = call(port, "POST", SYSTEMS, subject,
                systemBody(randomCode("sys-priv"), "OUTBOUND", "API", "{\"baseUrl\":\"http://10.0.0.5/orders\"}"));
        assertThat(privateApi.status()).as("body: %s", privateApi.body()).isEqualTo(400);
        assertThat(String.join("\n", errorsOf(privateApi))).contains("baseUrl").contains("private");

        Response metadataMq = call(port, "POST", SYSTEMS, subject,
                systemBody(randomCode("sys-meta"), "OUTBOUND", "MQ",
                        "{\"host\":\"169.254.169.254\",\"port\":1414,\"queue\":\"Q\"}"));
        assertThat(metadataMq.status()).as("body: %s", metadataMq.body()).isEqualTo(400);
        assertThat(String.join("\n", errorsOf(metadataMq))).contains("host").contains("link-local");

        Response offList = call(port, "POST", SYSTEMS, subject,
                systemBody(randomCode("sys-offlist"), "OUTBOUND", "API", "{\"baseUrl\":\"https://not-allowed.example/\"}"));
        assertThat(offList.status()).as("body: %s", offList.body()).isEqualTo(400);
        assertThat(String.join("\n", errorsOf(offList))).contains("allow-list");

        // the allowed destination saves - and no lookup happened to decide it (pre-DNS allow-list; the suite's
        // fake resolver is never consulted for ok.e5.test at save time)
        Response allowedMq = call(port, "POST", SYSTEMS, subject,
                systemBody(randomCode("sys-okmq"), "OUTBOUND", "MQ",
                        "{\"host\":\"ok.e5.test\",\"port\":1414,\"queue\":\"Q\"}"));
        assertThat(allowedMq.status()).as("body: %s", allowedMq.body()).isEqualTo(201);

        Response created = call(port, "POST", SYSTEMS, subject,
                systemBody(randomCode("sys-edit"), "OUTBOUND", "API", "{\"baseUrl\":\"https://a.partner.example/\"}"));
        assertThat(created.status()).as("body: %s", created.body()).isEqualTo(201);
        String id = created.json().path("id").asString();

        Response refusedEdit = call(port, "PUT", SYSTEMS + "/" + id, subject,
                "{\"type\":\"API\",\"settings\":{\"baseUrl\":\"http://[::ffff:10.0.0.5]/\"}}");
        assertThat(refusedEdit.status()).as("body: %s", refusedEdit.body()).isEqualTo(400);
        assertThat(String.join("\n", errorsOf(refusedEdit))).contains("private");
        assertThat(scalar("SELECT settings->>'baseUrl' FROM public.system WHERE id = ?::uuid", id))
                .as("a refused edit changes nothing").isEqualTo("https://a.partner.example/");
    }

    private static List<String> errorsOf(Response response) {
        List<String> errors = new ArrayList<>();
        response.json().path("errors").forEach(node -> errors.add(node.asString()));
        return errors;
    }
}
