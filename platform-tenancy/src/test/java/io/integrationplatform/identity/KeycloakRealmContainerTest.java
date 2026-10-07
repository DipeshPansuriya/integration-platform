package io.integrationplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * E4 (T-097): the realm file, imported into the pinned Keycloak, behaves as ADR-007 Decisions 1 and 3 say - and the
 * {@link KeycloakRealmContainer} fixture the later lanes reuse works. Container group (R03).
 */
@Tag("container")
class KeycloakRealmContainerTest {

    private static final String AUDIENCE = "platform-api-" + KeycloakRealmContainer.randomToken(4);

    private static KeycloakRealmContainer keycloak;

    @BeforeAll
    static void start() {
        keycloak = new KeycloakRealmContainer(AUDIENCE, "http://localhost:1/login/oauth2/code/keycloak",
                "http://localhost:1/");
        keycloak.start();
    }

    @AfterAll
    static void stop() {
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    @Test
    void theImportedRealmKeepsItsSettings() {
        JsonNode realm = keycloak.realmRepresentation();
        assertThat(realm.get("realm").asString()).isEqualTo("platform");
        assertThat(realm.get("registrationAllowed").asBoolean()).isFalse();
        assertThat(realm.get("accessTokenLifespan").asInt()).isEqualTo(300);
        assertThat(realm.get("bruteForceProtected").asBoolean()).isTrue();
        assertThat(keycloak.issuerUri()).endsWith("/realms/platform");
    }

    @Test
    void aClientCreatedWithTheDefaultScopeGetsAFiveMinuteTokenForThePlatformAudience() {
        KeycloakRealmContainer.ClientCredentials client = keycloak.createConfidentialClient("source-with-scope", true);
        JsonNode claims = claims(keycloak.clientCredentialsToken(client.clientId(), client.secret()));
        assertThat(audiences(claims)).contains(AUDIENCE);
        assertThat(claims.get("iss").asString()).isEqualTo(keycloak.issuerUri());
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(300);
        assertThat(claims.get("azp").asString()).isEqualTo("source-with-scope");
    }

    @Test
    void aClientCreatedWithoutTheScopeGetsATokenWithoutThePlatformAudience() {
        KeycloakRealmContainer.ClientCredentials client = keycloak.createConfidentialClient("source-without", false);
        JsonNode claims = claims(keycloak.clientCredentialsToken(client.clientId(), client.secret()));
        assertThat(audiences(claims)).doesNotContain(AUDIENCE);
    }

    @Test
    void theConsoleClientRefusesThePasswordGrant() {
        KeycloakRealmContainer.RealmUser user = keycloak.createUser("password-grant-user");
        HttpResponse<String> passwordGrant = keycloak.tokenEndpoint(Map.of("grant_type", "password",
                "client_id", KeycloakRealmContainer.CONSOLE_CLIENT, "client_secret", keycloak.consoleClientSecret(),
                "username", user.username(), "password", user.password()));
        assertThat(passwordGrant.statusCode()).isEqualTo(400);
        assertThat(passwordGrant.body()).contains("unauthorized_client");
    }

    @Test
    void thePlatformAdminServiceAccountManagesClientsButNotUsers() {
        String token = keycloak.clientCredentialsToken(KeycloakRealmContainer.ADMIN_CLIENT,
                keycloak.platformAdminClientSecret());
        String admin = keycloak.issuerUri().replace("/realms/platform", "/admin/realms/platform");
        assertThat(keycloak.send("GET", admin + "/clients?clientId=platform-console", token, null).statusCode())
                .isEqualTo(200);
        assertThat(keycloak.send("GET", admin + "/users", token, null).statusCode()).isEqualTo(403);
    }

    @Test
    void aUserCanCarryATestOnlyClaimForTheConsoleClient() {
        KeycloakRealmContainer.RealmUser user = keycloak.createUser("claim-user", Map.of("tenant", "tenant-b"));
        assertThat(user.subject()).isNotBlank();
        assertThat(user.toString()).doesNotContain(user.password());
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static JsonNode claims(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        return KeycloakRealmContainer.read(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    private static java.util.List<String> audiences(JsonNode claims) {
        JsonNode aud = claims.path("aud");
        java.util.List<String> values = new java.util.ArrayList<>();
        if (aud.isArray()) {
            aud.forEach(a -> values.add(a.asString()));
        } else if (!aud.isMissingNode() && !aud.isNull()) {
            values.add(aud.asString());
        }
        return values;
    }
}
