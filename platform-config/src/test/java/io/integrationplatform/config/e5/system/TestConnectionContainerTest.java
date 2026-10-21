package io.integrationplatform.config.e5.system;

import static io.integrationplatform.config.e5.system.E5SystemFixture.SYSTEMS;
import static io.integrationplatform.config.e5.system.E5SystemFixture.call;
import static io.integrationplatform.config.e5.system.E5SystemFixture.randomCode;
import static io.integrationplatform.config.e5.system.E5SystemFixture.systemBody;
import static io.integrationplatform.config.e5.system.E5SystemSuiteApp.WIREMOCK;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.integrationplatform.config.e5.destination.WireMockDestination;
import io.integrationplatform.config.e5.system.E5SystemFixture.Response;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * T-104 (E5-F1-S2, PostgreSQL + the real identity chain + WireMock): test-connection on a running system. WireMock is
 * the allowed destination (reached through the guard's injected resolver and dialer, see {@link WireMockDestination});
 * the allow-list of this suite is {@code *.e5.test}; the rate limit is 3 per user per minute here, and each scenario
 * uses a fresh configurator so one scenario's attempts never count against another's.
 *
 * <pre>
 * Scenario: An allowed API destination is tested
 * &#64;negative &#64;security Scenario Outline: Forbidden destinations are refused
 *   Examples: not on the allow-list | DNS to 127.0.0.1 | DNS to 10/8, 172.16/12, 192.168/16 |
 *             redirects to a loopback or private address | a non-HTTP(S) scheme
 * &#64;negative &#64;security Scenario: Test-connection is rate-limited
 * &#64;negative Scenario: Other types are not tested yet
 * </pre>
 */
@Tag("container")
@SpringBootTest(classes = E5SystemSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "platform.config.destinations.allowed-hosts=*.e5.test",
    "platform.config.test-connection.rate-limit.limit=3",
    "platform.config.test-connection.rate-limit.window=60s"})
class TestConnectionContainerTest {

    private static final String AUDIT_LOGGER = "io.integrationplatform.config.system.application.TestConnectionAudit";

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meters;

    private ListAppender<ILoggingEvent> audit;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        E5SystemFixture.wire(registry);
        WIREMOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void captureAuditAndResetStubs() {
        WIREMOCK.reset();
        WIREMOCK.clearDialled();
        WIREMOCK.resolve("loop.e5.test", WireMockDestination.literal("127.0.0.1"));
        WIREMOCK.resolve("ten.e5.test", WireMockDestination.literal("10.20.30.40"));
        WIREMOCK.resolve("sixteen.e5.test", WireMockDestination.literal("172.16.0.9"));
        WIREMOCK.resolve("lan.e5.test", WireMockDestination.literal("192.168.1.50"));
        WIREMOCK.resolve("outside.example.org", WireMockDestination.PINNED);
        audit = new ListAppender<>();
        audit.start();
        ((Logger) LoggerFactory.getLogger(AUDIT_LOGGER)).addAppender(audit);
    }

    @AfterEach
    void stopCapture() {
        ((Logger) LoggerFactory.getLogger(AUDIT_LOGGER)).detachAppender(audit);
    }

    private static String configuratorOfA() {
        return E5SystemFixture.newUser(E5SystemFixture.tenantA().id(), "CONFIGURATOR");
    }

    /** Registers a system through the API and answers its id. */
    private String register(String subject, String type, String settings) {
        Response created = call(port, "POST", SYSTEMS, subject,
                systemBody(randomCode("sys-tc"), "OUTBOUND", type, settings));
        assertThat(created.status()).as("register, body: %s", created.body()).isEqualTo(201);
        return created.json().path("id").asString();
    }

    private Response testConnection(String subject, String systemId) {
        return call(port, "POST", SYSTEMS + "/" + systemId + "/test-connection", subject, null);
    }

    /** The audit lines of this test as "outcome/reason" plus the full key-value text, for assertions. */
    private List<String> auditLines() {
        return audit.list.stream().map(event -> event.getKeyValuePairs() == null ? event.getFormattedMessage()
                : event.getKeyValuePairs().stream().map(pair -> pair.key + "=" + pair.value)
                        .collect(Collectors.joining(" "))).toList();
    }

    @Test
    @DisplayName("Scenario: An allowed API destination is tested - success, the response time, and the attempt audited")
    void anAllowedDestinationIsTested() {
        WIREMOCK.stubGet("/health", 200, "\"body\":\"ok\"");
        String subject = configuratorOfA();
        String systemId = register(subject, "API", "{\"baseUrl\":\"" + WireMockDestination.address("/health") + "\"}");

        Response answer = testConnection(subject, systemId);

        assertThat(answer.status()).as("body: %s", answer.body()).isEqualTo(200);
        JsonNode result = answer.json();
        assertThat(result.path("systemId").asString()).isEqualTo(systemId);
        assertThat(result.path("result").asString()).isEqualTo("SUCCESS");
        assertThat(result.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(result.path("responseTimeMs").isNumber()).as("the response time is shown").isTrue();
        assertThat(WIREMOCK.requestCount()).isEqualTo(1);
        assertThat(auditLines()).singleElement().asString()
                .contains("event=system.test_connection").contains("outcome=SUCCESS")
                .contains("tenant=" + E5SystemFixture.tenantA().id()).contains("system=" + systemId)
                .doesNotContain("/health");
        assertThat(meters.find("integration.destination.checks").tag("outcome", "SUCCESS").counter())
                .as("the check is metered").isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "resolves by DNS to 127.0.0.1                | http://loop.e5.test:8080/         | REFUSED_ADDRESS",
        "resolves by DNS to 10/8                     | http://ten.e5.test:8080/          | REFUSED_ADDRESS",
        "resolves by DNS to 172.16/12                | http://sixteen.e5.test/           | REFUSED_ADDRESS",
        "resolves by DNS to 192.168/16               | https://lan.e5.test/              | REFUSED_ADDRESS"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario Outline: Forbidden destinations are refused before any data is sent, and audited")
    void forbiddenDestinationsAreRefused(String example, String address, String reason) {
        String subject = configuratorOfA();
        String systemId = register(subject, "API", "{\"baseUrl\":\"" + address + "\"}");

        Response answer = testConnection(subject, systemId);

        assertRefusedAndAudited(answer, systemId, reason);
        assertThat(WIREMOCK.dialled()).as("nothing was dialled: no data sent").isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: a host off the allow-list is refused (at save since T-124, and at test time)")
    void aHostOffTheAllowListIsRefused() {
        String subject = configuratorOfA();
        Response atSave = call(port, "POST", SYSTEMS, subject,
                systemBody(randomCode("sys-out"), "OUTBOUND", "API", "{\"baseUrl\":\"http://outside.example.org:8080/\"}"));
        assertThat(atSave.status()).as("the save-time guard refuses it").isEqualTo(400);
        assertThat(atSave.body()).contains("settings.baseUrl is refused: the host is not on the destination allow-list");

        // a row that bypassed the API (an import, a row saved before T-124) is still refused by the guard at test time
        String systemId = UUID.randomUUID().toString();
        E5SystemFixture.execute("INSERT INTO public.system (id, tenant_id, code, name, direction, type, settings)"
                + " VALUES (?::uuid, ?::uuid, ?, 'legacy outside', 'TARGET', 'API', ?::jsonb)", systemId,
                E5SystemFixture.tenantA().id().toString(), randomCode("sys-out-row"),
                "{\"baseUrl\":\"http://outside.example.org:8080/\"}");

        Response answer = testConnection(subject, systemId);

        assertRefusedAndAudited(answer, systemId, "NOT_ON_ALLOW_LIST");
        assertThat(WIREMOCK.dialled()).isEmpty();
    }

    @ParameterizedTest(name = "redirects to {0}")
    @CsvSource(delimiter = '|', value = {
        "a loopback address  | http://127.0.0.1:8080/admin",
        "a private address   | http://lan.e5.test/"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario Outline: a destination that redirects to a loopback or private address is refused")
    void aRedirectToTheInsideIsRefused(String example, String location) {
        WIREMOCK.stubRedirect("/hop", 302, location);
        String subject = configuratorOfA();
        String systemId = register(subject, "API",
                "{\"baseUrl\":\"" + WireMockDestination.address("/hop") + "\",\"followRedirects\":true}");

        Response answer = testConnection(subject, systemId);

        assertRefusedAndAudited(answer, systemId, "REFUSED_ADDRESS");
        assertThat(WIREMOCK.dialled()).as("only WireMock's own hop was dialled").hasSize(1);
        assertThat(WIREMOCK.requestCount()).isEqualTo(1);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario Outline: a destination with a non-HTTP(S) scheme is refused (at save, and at test time)")
    void aNonHttpSchemeIsRefused() {
        String subject = configuratorOfA();
        Response atSave = call(port, "POST", SYSTEMS, subject,
                systemBody(randomCode("sys-ftp"), "OUTBOUND", "API", "{\"baseUrl\":\"ftp://files.e5.test/\"}"));
        assertThat(atSave.status()).as("the settings schema refuses it at save").isEqualTo(400);

        // a row that bypassed the API (an import, an older schema) is still refused by the guard at test time
        String systemId = UUID.randomUUID().toString();
        E5SystemFixture.execute("INSERT INTO public.system (id, tenant_id, code, name, direction, type, settings)"
                + " VALUES (?::uuid, ?::uuid, ?, 'legacy ftp', 'TARGET', 'API', ?::jsonb)", systemId,
                E5SystemFixture.tenantA().id().toString(), randomCode("sys-ftp-row"),
                "{\"baseUrl\":\"ftp://files.e5.test/\"}");

        Response answer = testConnection(subject, systemId);

        assertRefusedAndAudited(answer, systemId, "SCHEME_NOT_ALLOWED");
        assertThat(WIREMOCK.dialled()).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: Test-connection is rate-limited - over the limit it answers 429, and that is audited")
    void testConnectionIsRateLimited() {
        WIREMOCK.stubGet("/health", 200, "");
        String subject = configuratorOfA();
        String systemId = register(subject, "API", "{\"baseUrl\":\"" + WireMockDestination.address("/health") + "\"}");
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(testConnection(subject, systemId).status()).as("attempt " + attempt).isEqualTo(200);
        }

        Response limited = testConnection(subject, systemId);

        assertThat(limited.status()).as("body: %s", limited.body()).isEqualTo(429);
        assertThat(limited.json().path("refusal").asString()).isEqualTo("TEST_CONNECTION_RATE_LIMITED");
        assertThat(WIREMOCK.requestCount()).as("the limited attempt made no call").isEqualTo(3);
        assertThat(auditLines()).last().asString().contains("outcome=RATE_LIMITED")
                .contains("system=" + systemId);
        // the limit is per user: another configurator of the same tenant is not limited by it
        assertThat(testConnection(configuratorOfA(), systemId).status()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "DB   | {\"host\":\"db.e5.test\",\"port\":5432,\"database\":\"orders\",\"user\":\"ipass_orders\"} | E16",
        "MQ   | {\"host\":\"mq.e5.test\",\"port\":1414,\"queue\":\"Q\"}           | E10",
        "FILE | {\"rootPath\":\"/data/out\"}                                       | E16"})
    @Tag("negative")
    @DisplayName("Scenario: Other types are not tested yet - the answer names the adapter's epic")
    void otherTypesAreNotTestedYet(String type, String settings, String epic) {
        String subject = configuratorOfA();
        String systemId = register(subject, type, settings);

        Response answer = testConnection(subject, systemId);

        assertThat(answer.status()).as("body: %s", answer.body()).isEqualTo(200);
        assertThat(answer.json().path("result").asString()).isEqualTo("NOT_AVAILABLE_YET");
        assertThat(answer.json().path("message").asString()).contains(epic).contains("adapter");
        assertThat(WIREMOCK.dialled()).isEmpty();
        assertThat(auditLines()).singleElement().asString().contains("outcome=NOT_AVAILABLE_YET");
    }

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("Another tenant's system answers 404 and nothing is dialled")
    void anotherTenantsSystemIs404() {
        String systemOfA = register(configuratorOfA(), "API",
                "{\"baseUrl\":\"" + WireMockDestination.address("/health") + "\"}");
        String configuratorOfB = E5SystemFixture.newUser(E5SystemFixture.tenantB().id(), "CONFIGURATOR");

        assertThat(testConnection(configuratorOfB, systemOfA).status()).isEqualTo(404);
        assertThat(WIREMOCK.dialled()).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Only editors run test-connection: a READ_ONLY user is refused 403 before any lookup")
    void readOnlyCannotTest() {
        String systemId = register(configuratorOfA(), "API",
                "{\"baseUrl\":\"" + WireMockDestination.address("/health") + "\"}");
        String reader = E5SystemFixture.newUser(E5SystemFixture.tenantA().id(), "READ_ONLY");

        assertThat(testConnection(reader, systemId).status()).isEqualTo(403);
        assertThat(WIREMOCK.dialled()).isEmpty();
    }

    private void assertRefusedAndAudited(Response answer, String systemId, String reason) {
        assertThat(answer.status()).as("body: %s", answer.body()).isEqualTo(200);
        assertThat(answer.json().path("result").asString()).isEqualTo("REFUSED");
        assertThat(answer.json().path("reason").asString()).isEqualTo(reason);
        assertThat(answer.json().path("responseTimeMs").isNull()).isTrue();
        assertThat(auditLines()).singleElement().asString()
                .contains("event=system.test_connection").contains("outcome=REFUSED").contains("reason=" + reason)
                .contains("system=" + systemId);
    }
}
