package io.integrationplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.TenancyTestImages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * E4 (T-097): the realm file compose mounts and the tests import - checked without Docker (bare {@code mvnw test},
 * R03). ADR-007 Decisions 1 and 3; R05 and R07.
 */
class PlatformRealmFileTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[A-Z][A-Z0-9_]*}");

    private final JsonNode realm = KeycloakRealmContainer.read(readRealm());

    @Test
    void realmPlatformRefusesSelfRegistrationAndBoundsTokensAndGuessing() {
        assertThat(realm.get("realm").asString()).isEqualTo("platform");
        assertThat(realm.get("enabled").asBoolean()).isTrue();
        assertThat(realm.get("registrationAllowed").asBoolean()).as("self-registration").isFalse();
        assertThat(realm.get("accessTokenLifespan").asInt()).as("access token lifespan, seconds").isEqualTo(300);
        assertThat(realm.get("bruteForceProtected").asBoolean()).as("brute-force protection").isTrue();
        // OTP is available (the realm's default browser flow offers it) but not required (PRD section 9 is open).
        assertThat(realm.path("otpPolicyType").asString()).isEqualTo("totp");
        assertThat(realm.path("requiredActions").isMissingNode()
                || !realm.get("requiredActions").toString().contains("\"defaultAction\":true"))
                .as("no required action is forced on every user").isTrue();
    }

    @Test
    void theAudienceScopeIsADefaultOfEveryClientCreatedLater() {
        JsonNode scope = clientScope("platform-api-audience");
        JsonNode mapper = scope.get("protocolMappers").get(0);
        assertThat(mapper.get("protocolMapper").asString()).isEqualTo("oidc-audience-mapper");
        assertThat(mapper.get("config").get("included.custom.audience").asString()).isEqualTo("${OIDC_AUDIENCE}");
        assertThat(mapper.get("config").get("access.token.claim").asString()).isEqualTo("true");
        assertThat(names(realm.get("defaultDefaultClientScopes"))).contains("platform-api-audience");
        for (JsonNode client : realm.get("clients")) {
            assertThat(names(client.get("defaultClientScopes")))
                    .as(client.get("clientId").asString()).contains("platform-api-audience");
        }
    }

    @Test
    void theConsoleIsAConfidentialAuthorizationCodeClientWithPkce() {
        JsonNode console = client("platform-console");
        assertThat(console.get("publicClient").asBoolean()).isFalse();
        assertThat(console.get("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(console.get("directAccessGrantsEnabled").asBoolean()).as("no password grant").isFalse();
        assertThat(console.get("implicitFlowEnabled").asBoolean()).isFalse();
        assertThat(console.get("serviceAccountsEnabled").asBoolean()).isFalse();
        assertThat(console.get("attributes").get("pkce.code.challenge.method").asString()).isEqualTo("S256");
        assertThat(names(console.get("redirectUris"))).containsExactly("${CONSOLE_OIDC_REDIRECT_URI}");
    }

    @Test
    void thePlatformAdminServiceAccountHoldsOnlyClientManagement() {
        JsonNode admin = client("platform-admin");
        assertThat(admin.get("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(admin.get("standardFlowEnabled").asBoolean()).isFalse();
        assertThat(admin.get("directAccessGrantsEnabled").asBoolean()).isFalse();
        JsonNode account = null;
        for (JsonNode user : realm.get("users")) {
            if ("platform-admin".equals(user.path("serviceAccountClientId").asString(""))) {
                account = user;
            }
        }
        assertThat(account).as("the service account user").isNotNull();
        assertThat(names(account.get("realmRoles"))).isEmpty();
        assertThat(names(account.get("clientRoles").get("realm-management")))
                .containsExactlyInAnyOrder("manage-clients", "view-clients");
        assertThat(account.get("clientRoles").size()).isEqualTo(1);
    }

    @Test
    void everySecretInTheFileIsAnEnvironmentPlaceholder() {
        List<String> findings = new ArrayList<>();
        collectSecrets(realm, "", findings);
        assertThat(findings).isEmpty();
        assertThat(client("platform-console").get("secret").asString()).isEqualTo("${CONSOLE_OIDC_CLIENT_SECRET}");
        assertThat(client("platform-admin").get("secret").asString()).isEqualTo("${PLATFORM_ADMIN_CLIENT_SECRET}");
    }

    @Test
    void plantedLiteralSecretIsFlagged() { // R02: the check fails on a planted violation (in memory only)
        List<String> findings = new ArrayList<>();
        collectSecrets(KeycloakRealmContainer.read("{\"clients\":[{\"secret\":\"s3cr3t\"}],"
                + "\"users\":[{\"credentials\":[{\"value\":\"pw\"}]}]}"), "", findings);
        assertThat(findings).hasSize(2);
    }

    @Test
    void theKeycloakTagIsPinnedAndComposeUsesTheSameOne() throws IOException {
        assertThat(TenancyTestImages.KEYCLOAK).matches("quay\\.io/keycloak/keycloak:\\d+\\.\\d+\\.\\d+");
        Path compose = KeycloakRealmContainer.realmFile().getParent().getParent().resolve("compose.yaml");
        assertThat(Files.readString(compose)).contains("image: " + TenancyTestImages.KEYCLOAK);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static String readRealm() {
        try {
            return Files.readString(KeycloakRealmContainer.realmFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A {@code secret}, {@code password} or credential {@code value} must be a whole {@code ${VAR}} placeholder. */
    private static void collectSecrets(JsonNode node, String path, List<String> findings) {
        if (node.isObject()) {
            for (String field : node.propertyNames()) {
                JsonNode value = node.get(field);
                String at = path + "/" + field;
                boolean secretField = field.equalsIgnoreCase("secret") || field.toLowerCase().contains("password")
                        || path.endsWith("/credentials") && field.equals("value");
                if (secretField && value.isString() && !PLACEHOLDER.matcher(value.asString()).matches()) {
                    findings.add(at + " holds a literal");
                }
                collectSecrets(value, at, findings);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collectSecrets(element, path, findings);
            }
        }
    }

    private JsonNode client(String clientId) {
        for (JsonNode client : realm.get("clients")) {
            if (clientId.equals(client.get("clientId").asString())) {
                return client;
            }
        }
        throw new AssertionError("client " + clientId + " not in the realm file");
    }

    private JsonNode clientScope(String name) {
        for (JsonNode scope : realm.get("clientScopes")) {
            if (name.equals(scope.get("name").asString())) {
                return scope;
            }
        }
        throw new AssertionError("client scope " + name + " not in the realm file");
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        if (array != null) {
            array.forEach(n -> names.add(n.asString()));
        }
        return names;
    }
}
