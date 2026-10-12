package io.integrationplatform.config.e5.credential;

import static io.integrationplatform.config.e5.credential.E5CredentialFixture.MASK;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.OIDC_ISSUER;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.call;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.createSystem;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.newUser;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.scalar;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.tenantA;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.tenantB;
import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e5.credential.E5CredentialFixture.Response;
import io.integrationplatform.tenancy.port.ClientBootstrap;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * E5-F1-S3 (T-103; ADR-007 Decision 3) - source credentials over the real HTTP API on PostgreSQL, through the real
 * identity chain, role interceptor and source-credential lifecycle; the identity provider is the suite's recording
 * double ({@link E5CredentialSuiteApp#IDENTITY_PROVIDER}).
 *
 * <pre>
 * Scenario: Issue a source credential
 *   Given a tenant admin and a registered source system
 *   When they issue a credential
 *   Then a confidential Keycloak client is created, the secret is shown exactly once, and the action is audited
 *
 * &#64;negative &#64;security
 * Scenario: Secrets are write-only and never logged       (the source half: listed with a masked marker only)
 *
 * &#64;negative &#64;security
 * Scenario Outline: Only a tenant admin manages credentials
 *   Given a user holding only &lt;role&gt;
 *   When they issue, rotate or revoke a credential
 *   Then the server answers 403
 *   Examples: configurator | support | read-only
 *
 * &#64;negative &#64;security
 * Scenario: A revoked credential stops working at once    (here: the client bootstrap refuses it from the revoke on;
 *                                                          platform-app proves it with real Keycloak tokens)
 * &#64;negative
 * Scenario: Keycloak unavailable during issue            (here: 503 with a clear message, nothing half-created; the
 *                                                          five controls on the real adapter: platform-tenancy)
 * </pre>
 */
@Tag("container")
@SpringBootTest(classes = E5CredentialSuiteApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class SourceCredentialContainerTest {

    private static final E5CredentialSuiteApp.SuiteIdentityProvider IDP = E5CredentialSuiteApp.IDENTITY_PROVIDER;

    @LocalServerPort
    private int port;

    @Autowired
    private ClientBootstrap clientBootstrap;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        E5CredentialFixture.wire(registry);
    }

    @BeforeEach
    void resetProvider() {
        IDP.reset();
    }

    private static String issuePath(UUID system) {
        return "/api/v1/admin/systems/" + system + "/source-credentials";
    }

    private static String credentialPath(String credentialId, String action) {
        return "/api/v1/admin/source-credentials/" + credentialId + "/" + action;
    }

    private UUID sourceSystem(String admin) {
        return createSystem(port, admin, "INBOUND", null, null);
    }

    private Response issue(String subject, UUID system) {
        return call(port, "POST", issuePath(system), subject, "{\"customerId\":\"CUSTOMER-7\"}");
    }

    private static String row(String credentialId) {
        return scalar("SELECT status || '|' || idp_disable_pending || '|' || coalesce(revoke_after::text, '-')"
                + " FROM public.source_credential WHERE id = ?::uuid", credentialId);
    }

    @Test
    @DisplayName("Scenario: Issue a source credential - a confidential client, the secret shown exactly once, audited")
    void issueASourceCredential(CapturedOutput output) {
        String admin = tenantA().adminSubject();
        UUID system = sourceSystem(admin);
        List<String> statusDuringCreate = new ArrayList<>();
        IDP.duringCreate = clientId -> statusDuringCreate.add(scalar(
                "SELECT status FROM public.source_credential WHERE client_id = ?", clientId));

        Response issued = issue(admin, system);

        assertThat(issued.status()).as(issued.body()).isEqualTo(201);
        String id = issued.json().path("id").asString();
        String clientId = issued.json().path("clientId").asString();
        String secret = issued.json().path("secret").asString();
        assertThat(clientId).isEqualTo("src-" + id);
        assertThat(secret).hasSizeGreaterThanOrEqualTo(43);
        assertThat(issued.json().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(issued.json().path("systemId").asString()).isEqualTo(system.toString());
        assertThat(statusDuringCreate).as("ADR-007 D3 order: PENDING while the client is created").containsExactly(
                "PENDING");
        assertThat(IDP.created).contains(clientId);
        assertThat(row(id)).isEqualTo("ACTIVE|false|-");
        assertThat(scalar("SELECT issuer || '|' || customer_id FROM public.source_credential WHERE id = ?::uuid", id))
                .isEqualTo(OIDC_ISSUER + "|CUSTOMER-7");
        assertThat(scalar("SELECT count(*)::text FROM public.source_credential c WHERE c.id = ?::uuid"
                + " AND row_to_json(c)::text LIKE '%' || ? || '%'", id, secret))
                .as("the secret is never stored").isEqualTo("0");

        Response listed = call(port, "GET", issuePath(system), admin, null);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body()).contains(id).contains(MASK).doesNotContain(secret);
        assertThat(output).contains("event=credential.source_issued").contains(id).doesNotContain(secret);
        assertThat(clientBootstrap.resolve(OIDC_ISSUER.toString(), clientId)).as("usable at once").isPresent();
    }

    @Test
    @Tag("negative")
    @DisplayName("Issue refusals: another tenant's system 404, a target system 409, no customer 400 - nothing created")
    void issueRefusals() {
        String admin = tenantA().adminSubject();
        int createdBefore = IDP.created.size();
        assertThat(issue(admin, sourceSystem(tenantB().adminSubject())).status()).isEqualTo(404);
        assertThat(call(port, "POST", "/api/v1/admin/systems/not-a-uuid/source-credentials", admin,
                "{\"customerId\":\"C\"}").status()).isEqualTo(404);
        assertThat(issue(admin, createSystem(port, admin, "OUTBOUND", null, null)).status()).isEqualTo(409);
        assertThat(call(port, "POST", issuePath(sourceSystem(admin)), admin, "{}").status()).isEqualTo(400);
        assertThat(IDP.created).hasSize(createdBefore);
    }

    @Test
    @Tag("negative")
    @DisplayName("Scenario: Keycloak unavailable during issue - 503 with a clear message, no ACTIVE or PENDING row left")
    void keycloakUnavailableDuringIssue() {
        String admin = tenantA().adminSubject();
        UUID system = sourceSystem(admin);
        IDP.failCreate = true;

        Response failed = issue(admin, system);

        assertThat(failed.status()).isEqualTo(503);
        assertThat(failed.json().path("error").asString()).contains("identity provider").contains("try again later");
        assertThat(scalar("SELECT count(*)::text FROM public.source_credential WHERE source_system_id = ?::uuid"
                + " AND status IN ('ACTIVE', 'PENDING')", system.toString())).isEqualTo("0");
        assertThat(scalar("SELECT string_agg(status || '|' || idp_disable_pending, ',') FROM public.source_credential"
                + " WHERE source_system_id = ?::uuid", system.toString()))
                .as("the dead row waits for the reconciliation to disable any client a timeout left")
                .isEqualTo("REVOKED|true");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: A revoked credential stops working at once - row first, then the client; a 2nd revoke is 409")
    void aRevokedCredentialStopsAtOnce() {
        String admin = tenantA().adminSubject();
        Response issued = issue(admin, sourceSystem(admin));
        String id = issued.json().path("id").asString();
        String clientId = issued.json().path("clientId").asString();

        Response revoked = call(port, "POST", credentialPath(id, "revoke"), admin, null);

        assertThat(revoked.status()).as(revoked.body()).isEqualTo(200);
        assertThat(revoked.json().path("status").asString()).isEqualTo("REVOKED");
        assertThat(revoked.json().path("idpDisablePending").asBoolean()).isFalse();
        assertThat(IDP.disabled).contains(clientId);
        assertThat(clientBootstrap.resolve(OIDC_ISSUER.toString(), clientId)).as("refused from the revoke on").isEmpty();
        assertThat(call(port, "POST", credentialPath(id, "revoke"), admin, null).status()).isEqualTo(409);
    }

    @Test
    @Tag("negative")
    @DisplayName("Revoke with Keycloak down: effective at once, the client disable stays pending for the reconciliation")
    void revokeWithKeycloakDown() {
        String admin = tenantA().adminSubject();
        Response issued = issue(admin, sourceSystem(admin));
        String id = issued.json().path("id").asString();
        IDP.failDisable = true;

        Response revoked = call(port, "POST", credentialPath(id, "revoke"), admin, null);

        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.json().path("idpDisablePending").asBoolean()).isTrue();
        assertThat(row(id)).startsWith("REVOKED|true|");
        assertThat(clientBootstrap.resolve(OIDC_ISSUER.toString(), issued.json().path("clientId").asString()))
                .isEmpty();
    }

    @Test
    @DisplayName("Rotate = issue a second credential: both valid, the old one stops after the 7-day grace window")
    void rotate() {
        String admin = tenantA().adminSubject();
        Response first = issue(admin, sourceSystem(admin));
        String oldId = first.json().path("id").asString();

        Response rotated = call(port, "POST", credentialPath(oldId, "rotate"), admin, null);

        assertThat(rotated.status()).as(rotated.body()).isEqualTo(201);
        String newId = rotated.json().path("id").asString();
        assertThat(newId).isNotEqualTo(oldId);
        assertThat(rotated.json().path("replaces").asString()).isEqualTo(oldId);
        assertThat(rotated.json().path("secret").asString()).hasSizeGreaterThanOrEqualTo(43)
                .isNotEqualTo(first.json().path("secret").asString());
        Instant revokeAfter = Instant.parse(rotated.json().path("replacedRevokeAfter").asString());
        assertThat(revokeAfter).isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(120),
                Instant.now().plus(Duration.ofDays(7)).plusSeconds(120));
        assertThat(row(oldId)).startsWith("ACTIVE|false|");
        assertThat(row(newId)).isEqualTo("ACTIVE|false|-");
        assertThat(clientBootstrap.resolve(OIDC_ISSUER.toString(), first.json().path("clientId").asString()))
                .as("the old credential still works in the grace window").isPresent();
        assertThat(call(port, "POST", credentialPath(oldId, "rotate"), admin, null).status())
                .as("already being rotated").isEqualTo(409);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario Outline: only a tenant admin issues, rotates or revokes - configurator, support, read-only: 403")
    void onlyATenantAdminManagesCredentials() {
        String admin = tenantA().adminSubject();
        UUID system = sourceSystem(admin);
        String id = issue(admin, system).json().path("id").asString();
        int created = IDP.created.size();
        for (String role : List.of("CONFIGURATOR", "SUPPORT", "READ_ONLY")) {
            String subject = newUser(tenantA().id(), role);
            assertThat(issue(subject, system).status()).as(role + " issue").isEqualTo(403);
            assertThat(call(port, "POST", credentialPath(id, "rotate"), subject, null).status()).as(role + " rotate")
                    .isEqualTo(403);
            assertThat(call(port, "POST", credentialPath(id, "revoke"), subject, null).status()).as(role + " revoke")
                    .isEqualTo(403);
        }
        assertThat(IDP.created).as("no client created by a refused call").hasSize(created);
        assertThat(row(id)).isEqualTo("ACTIVE|false|-");
        assertThat(scalar("SELECT count(*)::text FROM public.source_credential WHERE source_system_id = ?::uuid",
                system.toString())).isEqualTo("1");
    }

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("@tenant: another tenant's credential cannot be rotated or revoked (404) and stays active")
    void anotherTenantsCredential() {
        String foreignAdmin = tenantB().adminSubject();
        Response foreign = issue(foreignAdmin, sourceSystem(foreignAdmin));
        String foreignId = foreign.json().path("id").asString();
        String admin = tenantA().adminSubject();

        assertThat(call(port, "POST", credentialPath(foreignId, "revoke"), admin, null).status()).isEqualTo(404);
        assertThat(call(port, "POST", credentialPath(foreignId, "rotate"), admin, null).status()).isEqualTo(404);
        assertThat(call(port, "GET", issuePath(UUID.fromString(foreign.json().path("systemId").asString())), admin,
                null).status()).isEqualTo(404);
        assertThat(row(foreignId)).isEqualTo("ACTIVE|false|-");
    }
}
