package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin.CreatedClient;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin.IdentityProviderAdminException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * T-186 (ADR-007 Decisions 3 and 4, S-j; R13): the identity-provider admin adapter, against a mock Keycloak on the
 * adapter's REAL client (the shared {@code identity-provider} breaker and retry stay in place; only the transport is
 * mocked). Every platform-created client carries the {@value KeycloakAdminAdapter#AUDIENCE_SCOPE} default scope
 * explicitly; a create is never retried; disabling looks the client up (an idempotent read, retried on a transient
 * answer) and then disables it.
 */
@ExtendWith(OutputCaptureExtension.class)
class KeycloakAdminAdapterTest {

    private static final String ISSUER = "http://idp.test/realms/platform";
    private static final String TOKEN = ISSUER + "/protocol/openid-connect/token";
    private static final String ADMIN = "http://idp.test/admin/realms/platform";

    private final String adminSecret = UUID.randomUUID().toString();
    private TestObservationRegistry observations;
    private RestTemplate rest;
    private MockRestServiceServer keycloak;
    private KeycloakAdminAdapter adapter;

    @BeforeEach
    void adapter() {
        observations = TestObservationRegistry.create();
        IdentityProviderSettings fast = new IdentityProviderSettings(3, Duration.ofMillis(20), 2.0, 0.5,
                Duration.ofMillis(100), 5, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofHours(1));
        rest = KeycloakAdminAdapter.restTemplate(fast, new SimpleMeterRegistry(), observations);
        keycloak = MockRestServiceServer.bindTo(rest).build();
        adapter = new KeycloakAdminAdapter(ISSUER, "platform-admin", () -> adminSecret, rest, observations);
    }

    private void adminToken() {
        keycloak.expect(once(), requestTo(TOKEN))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "client_credentials", "client_id", "platform-admin",
                        "client_secret", adminSecret)))
                .andRespond(withSuccess("{\"access_token\":\"admin-token\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":60}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("S-j: a created client ALWAYS names the platform-api-audience default scope; its secret is returned once")
    void aCreatedClientCarriesTheAudienceScopeExplicitly() {
        AtomicReference<String> sentSecret = new AtomicReference<>();
        adminToken();
        keycloak.expect(once(), requestTo(ADMIN + "/clients"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> org.assertj.core.api.Assertions.assertThat(
                        request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer admin-token"))
                .andExpect(jsonPath("$.clientId").value("src-erp-a"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.publicClient").value(false))
                .andExpect(jsonPath("$.serviceAccountsEnabled").value(true))
                .andExpect(jsonPath("$.standardFlowEnabled").value(false))
                .andExpect(jsonPath("$.directAccessGrantsEnabled").value(false))
                .andExpect(jsonPath("$.defaultClientScopes", Matchers.hasItem(KeycloakAdminAdapter.AUDIENCE_SCOPE)))
                .andExpect(request -> sentSecret.set(new tools.jackson.databind.json.JsonMapper()
                        .readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                .getBodyAsString()).get("secret").asString()))
                .andRespond(withCreatedEntity(URI.create(ADMIN + "/clients/" + UUID.randomUUID())));

        CreatedClient created = adapter.createConfidentialClient("src-erp-a");

        keycloak.verify();
        assertThat(created.clientId()).isEqualTo("src-erp-a");
        assertThat(created.secret()).isEqualTo(sentSecret.get()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(created.toString()).doesNotContain(created.secret());
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(KeycloakAdminAdapter.OBSERVATION)
                .that().hasLowCardinalityKeyValue("operation", "create_client")
                .hasLowCardinalityKeyValue("outcome", "success");
    }

    @Test
    @Tag("negative")
    @DisplayName("R13: a client create is never retried - a 503 answer fails once with a clear error, nothing else sent")
    void aCreateIsNeverRetried() {
        adminToken();
        keycloak.expect(once(), requestTo(ADMIN + "/clients"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> adapter.createConfidentialClient("src-erp-a"))
                .isInstanceOf(IdentityProviderAdminException.class)
                .hasMessageContaining("src-erp-a")
                .hasMessageNotContaining(adminSecret);
        keycloak.verify();
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(KeycloakAdminAdapter.OBSERVATION)
                .that().hasLowCardinalityKeyValue("outcome", "error");
    }

    @Test
    @Tag("negative")
    @DisplayName("A client id that already exists is a clear conflict, never a second client")
    void anExistingClientIsAConflict() {
        adminToken();
        keycloak.expect(once(), requestTo(ADMIN + "/clients")).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> adapter.createConfidentialClient("src-erp-a"))
                .isInstanceOf(IdentityProviderAdminException.class)
                .hasMessageContaining("already exists");
        keycloak.verify();
    }

    @Test
    @DisplayName("Disable: the lookup (an idempotent read) is retried on a transient answer, then the client is disabled")
    void disableLooksUpThenDisables() {
        String id = UUID.randomUUID().toString();
        adminToken();
        keycloak.expect(once(), requestTo(ADMIN + "/clients?clientId=src-erp-a"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        keycloak.expect(once(), requestTo(ADMIN + "/clients?clientId=src-erp-a"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"" + id + "\",\"clientId\":\"src-erp-a\",\"enabled\":true}]",
                        MediaType.APPLICATION_JSON));
        keycloak.expect(once(), requestTo(ADMIN + "/clients/" + id))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.enabled").value(false))
                .andRespond(withNoContent());

        adapter.disableClient("src-erp-a");

        keycloak.verify();
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(KeycloakAdminAdapter.OBSERVATION)
                .that().hasLowCardinalityKeyValue("operation", "disable_client");
    }

    @Test
    @DisplayName("Disable: a client the provider no longer has is already disabled - nothing to write")
    void disablingAMissingClientIsDone() {
        adminToken();
        keycloak.expect(once(), requestTo(ADMIN + "/clients?clientId=gone")).andRespond(
                withSuccess("[]", MediaType.APPLICATION_JSON));

        adapter.disableClient("gone");

        keycloak.verify();
    }

    @Test
    @Tag("negative")
    @DisplayName("Disable: a refused write is a clear failure for the caller (the revoke keeps idp_disable_pending)")
    void aFailedDisableThrows() {
        String id = UUID.randomUUID().toString();
        adminToken();
        keycloak.expect(once(), requestTo(ADMIN + "/clients?clientId=src-erp-a")).andRespond(withSuccess(
                "[{\"id\":\"" + id + "\",\"clientId\":\"src-erp-a\"}]", MediaType.APPLICATION_JSON));
        keycloak.expect(times(1), requestTo(ADMIN + "/clients/" + id)).andRespond(
                withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> adapter.disableClient("src-erp-a"))
                .isInstanceOf(IdentityProviderAdminException.class)
                .hasMessageContaining("src-erp-a");
        keycloak.verify();
    }

    @Test
    @Tag("negative")
    @DisplayName("A refused admin token (wrong secret) is a clear failure, the secret never in the message")
    void aRefusedAdminTokenFails() {
        keycloak.expect(once(), requestTo(TOKEN)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> adapter.disableClient("src-erp-a"))
                .isInstanceOf(IdentityProviderAdminException.class)
                .hasMessageNotContaining(adminSecret);
        keycloak.verify();
    }

    private KeycloakAdminAdapter adapterWithAdminBase(String adminBase) {
        return new KeycloakAdminAdapter(ISSUER, adminBase, "platform-admin", () -> adminSecret, rest, observations);
    }

    @Test
    @DisplayName("Admin base: create goes to the admin base; the token and the issuer keep the public URL")
    void anAdminBaseReplacesOnlyTheAdminHost() {
        KeycloakAdminAdapter internal = adapterWithAdminBase("http://keycloak:8080");
        adminToken();
        keycloak.expect(once(), requestTo("http://keycloak:8080/admin/realms/platform/clients"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withCreatedEntity(URI.create("http://keycloak:8080/admin/realms/platform/clients/"
                        + UUID.randomUUID())));

        internal.createConfidentialClient("src-erp-a");

        keycloak.verify();
        assertThat(internal.issuer()).isEqualTo(ISSUER);
    }

    @Test
    @DisplayName("Admin base: disable looks up and writes on the admin base; a trailing slash and a context path are kept")
    void disableUsesTheAdminBaseWithItsPath() {
        String id = UUID.randomUUID().toString();
        String base = "http://keycloak:8080/auth";
        KeycloakAdminAdapter internal = adapterWithAdminBase(base + "/");
        adminToken();
        keycloak.expect(once(), requestTo(base + "/admin/realms/platform/clients?clientId=src-erp-a"))
                .andRespond(withSuccess("[{\"id\":\"" + id + "\",\"clientId\":\"src-erp-a\"}]",
                        MediaType.APPLICATION_JSON));
        keycloak.expect(once(), requestTo(base + "/admin/realms/platform/clients/" + id))
                .andExpect(method(HttpMethod.PUT)).andRespond(withNoContent());

        internal.disableClient("src-erp-a");

        keycloak.verify();
    }

    @Test
    @DisplayName("Admin base unset (null or blank): exactly the issuer-derived admin URL, as before")
    void anUnsetAdminBaseFallsBackToTheIssuer() {
        for (String unset : new String[] {null, "", "   "}) {
            keycloak.reset();
            adminToken();
            keycloak.expect(once(), requestTo(ADMIN + "/clients")).andRespond(withCreatedEntity(
                    URI.create(ADMIN + "/clients/" + UUID.randomUUID())));

            adapterWithAdminBase(unset).createConfidentialClient("src-erp-a");

            keycloak.verify();
        }
    }

    @Test
    @DisplayName("Admin base: every trailing slash is dropped, so host// never yields //admin")
    void manyTrailingSlashesAreAllStripped() {
        KeycloakAdminAdapter internal = adapterWithAdminBase("http://keycloak:8080///");
        adminToken();
        keycloak.expect(once(), requestTo("http://keycloak:8080/admin/realms/platform/clients"))
                .andRespond(withCreatedEntity(URI.create("http://keycloak:8080/admin/realms/platform/clients/"
                        + UUID.randomUUID())));

        internal.createConfidentialClient("src-erp-a");

        keycloak.verify();
    }

    @Test
    @DisplayName("Issuer: kept exactly as configured (a trailing slash is NOT stripped - revoke/disable compare it); the URLs built from it stay well-formed")
    void theIssuerIsNotAlteredButTheCallUrlsStayWellFormed() {
        for (String configured : new String[] {ISSUER + "/", ISSUER + "///"}) {
            keycloak.reset();
            KeycloakAdminAdapter slashed = new KeycloakAdminAdapter(configured, "platform-admin",
                    () -> adminSecret, rest, observations);
            adminToken();
            keycloak.expect(once(), requestTo(ADMIN + "/clients")).andRespond(withCreatedEntity(
                    URI.create(ADMIN + "/clients/" + UUID.randomUUID())));

            slashed.createConfidentialClient("src-erp-a");

            keycloak.verify();
            assertThat(slashed.issuer()).isEqualTo(configured);
        }
        assertThat(adapter.issuer()).isEqualTo(ISSUER);
    }

    @Test
    @DisplayName("Admin base: a plain-http base on a public-looking host logs a WARN naming only the host; start-up is not blocked")
    void aPublicHttpAdminBaseWarnsWithoutEchoingTheValue(CapturedOutput output) {
        for (String publicHttp : new String[] {"http://idp.example.com:8080/secretpath", "http://8.8.8.8:8080",
                "http://172.32.0.1:8080", "http://192.169.0.1"}) {
            adapterWithAdminBase(publicHttp);
        }

        assertThat(output.getAll()).contains("WARN").contains("idp.example.com").contains("8.8.8.8")
                .contains("172.32.0.1").contains("192.169.0.1").doesNotContain("secretpath");
    }

    @Test
    @DisplayName("Admin base: internal names and addresses (loopback, RFC1918, dotless service name) and https do not warn")
    void anInternalOrHttpsAdminBaseDoesNotWarn(CapturedOutput output) {
        for (String quiet : new String[] {"http://keycloak:8080", "http://localhost:8080", "http://127.0.0.1:8080",
                "http://[::1]:8080", "http://10.1.2.3", "http://172.16.0.9:8080", "http://172.31.255.1",
                "http://192.168.1.5", "https://idp.example.com"}) {
            adapterWithAdminBase(quiet);
        }
        adapterWithAdminBase(null);

        assertThat(output.getAll()).doesNotContain("admin base").doesNotContain("idp.example.com");
    }

    @Test
    @Tag("negative")
    @DisplayName("Admin base: only a plain http(s) server URL - no other scheme, no credentials, no query, no fragment")
    void anUnsafeAdminBaseIsRefusedAtConstruction() {
        for (String bad : new String[] {"ftp://keycloak:8080", "file:///etc/passwd", "keycloak:8080", "http://",
                "http://admin:s3cret@keycloak:8080", "http://:s3cret@keycloak:8080", "http://keycloak:8080?x=1",
                "http://keycloak:8080#frag", "http://key cloak:8080", "gopher://keycloak"}) {
            assertThatThrownBy(() -> adapterWithAdminBase(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("s3cret");
        }
    }

    @Test
    @Tag("negative")
    @DisplayName("Admin base: the token endpoint is never moved - the secret form goes to the issuer, not the admin base")
    void theAdminSecretNeverGoesToTheAdminBase() {
        KeycloakAdminAdapter internal = adapterWithAdminBase("http://keycloak:8080");
        keycloak.expect(once(), requestTo(TOKEN)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> internal.createConfidentialClient("src-erp-a"))
                .isInstanceOf(IdentityProviderAdminException.class);

        keycloak.verify();
    }

    @Test
    @Tag("negative")
    @DisplayName("R13: an admin call that gets no answer is cut at the 5 s admin timeout (ADR-007 Decision 4)")
    void anAdminCallTimesOutAfterFiveSeconds() throws IOException {
        assertThat(KeycloakAdminAdapter.ADMIN_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread accept = Thread.ofVirtual().start(() -> {
                try (Socket held = silent.accept()) {
                    Thread.sleep(15_000);
                } catch (IOException | InterruptedException ignored) {
                    // the test ends the wait by closing the socket
                }
            });
            String silentIssuer = "http://127.0.0.1:" + silent.getLocalPort() + "/realms/platform";
            IdentityProviderAdmin real = new KeycloakAdminAdapter(silentIssuer, "platform-admin", () -> adminSecret,
                    KeycloakAdminAdapter.restTemplate(IdentityProviderSettings.defaults(), new SimpleMeterRegistry(),
                            TestObservationRegistry.create()), TestObservationRegistry.create());
            long started = System.nanoTime();
            assertThatThrownBy(() -> real.disableClient("src-erp-a")).isInstanceOf(IdentityProviderAdminException.class);
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertThat(took).isBetween(Duration.ofMillis(4_500), Duration.ofSeconds(9));
            accept.interrupt();
        }
    }
}
