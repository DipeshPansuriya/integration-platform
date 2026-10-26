package io.integrationplatform.f3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.system.application.DbAccountWidthCheck;
import io.integrationplatform.identity.KeycloakRealmContainer;
import io.integrationplatform.identity.KeycloakRealmContainer.ClientCredentials;
import io.integrationplatform.routing.adapter.db.DbAccountWidth;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * T183 (B30 live proof): approving a DB table whose target account is too wide is refused through the real Spring
 * wiring, end to end - admin API -> {@code ApprovedTableService} -> config's {@code DbAccountWidthCheck} (adapted by
 * the composition root) -> routing's {@code DbAccountWidth} -> the credentialed privilege probe against a real
 * partner Postgres. The unit tests (DbAccountWidthBridgeTest, DbAccountWidthRefusalTest) mock each half of that seam.
 *
 * <pre>
 * Scenario Outline: Approval is decided by the width of the target account
 *   Given a DB target system whose restricted account holds the privileges of &lt;account&gt; on the table
 *   And the account's password is in the credential vault
 *   When a CONFIGURATOR requests the table and a DIFFERENT APPROVER approves it
 *   Then the answer is &lt;answer&gt; and the approval row is &lt;state&gt;
 *   Examples: WIDE (ALL on the table) -> 409 DB_ACCOUNT_TOO_WIDE, PENDING_APPROVAL
 *             NARROW (INSERT, SELECT) -> 200 APPROVED
 *             NO CREDENTIAL in the vault -> 409 DB_ACCOUNT_UNVERIFIED, PENDING_APPROVAL (fail closed)
 * </pre>
 */
@Tag("container")
@ActiveProfiles({"showcase", "test"}) // 'test' unlocks DbTlsPolicy's disable mode: the partner Postgres has no TLS
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(DbApprovalWidthLiveContainerTest.GrantAppRole.class)
@DisplayName("T183 B30 live: a wide DB account is refused at approval, a narrow one approved")
class DbApprovalWidthLiveContainerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TABLE = "partner_orders";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            "http://localhost/login/oauth2/code/keycloak", "http://localhost/");
    /** The partner's database; its accounts are created by the suite, each with a password (no trust auth). */
    static final PostgreSQLContainer TARGET_DB = new PostgreSQLContainer(TestImages.POSTGRES);

    private static RestrictedLogin login;
    private static boolean tableCreated;

    @LocalServerPort
    int port;

    @Autowired
    ObjectProvider<DbAccountWidthCheck> configsCheck;

    @Autowired
    ObjectProvider<DbAccountWidth> routingsWidth;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KEYCLOAK.start();
        TARGET_DB.start();
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        registry.add("CONSOLE_URL", () -> "/");
        registry.add("platform.config.destinations.allowed-hosts", () -> "localhost");
        registry.add("platform.config.destinations.showcase-local-hosts", () -> "localhost");
        registry.add("platform.config.destinations.showcase-local-ports", () -> String.valueOf(TARGET_DB.getMappedPort(5432)));
        registry.add("platform.config.destinations.db-sslmode", () -> "disable"); // test environment only (R1)
        registry.add("platform.config.destinations.db-allowed-ports", () -> String.valueOf(TARGET_DB.getMappedPort(5432)));
        registry.add("IPASS_DB_TARGET_ENABLED", () -> "true"); // routing's DB slice: the DbAccountWidth bean
        byte[] kek = new byte[32];
        RANDOM.nextBytes(kek);
        registry.add("PLATFORM_CREDENTIAL_KEK_K1", () -> Base64.getEncoder().encodeToString(kek));
        registry.add("PLATFORM_CREDENTIAL_KEK_ACTIVE", () -> "K1");
    }

    /** The restricted login joins ipaas_app right after Flyway and before the schedulers start. */
    @TestConfiguration(proxyBeanMethods = false)
    static class GrantAppRole {

        @Bean
        org.flywaydb.core.api.callback.Callback t183GrantAppRoleAfterMigrate() {
            return new org.flywaydb.core.api.callback.Callback() {
                @Override
                public boolean supports(org.flywaydb.core.api.callback.Event event,
                        org.flywaydb.core.api.callback.Context context) {
                    return event == org.flywaydb.core.api.callback.Event.AFTER_MIGRATE;
                }

                @Override
                public boolean canHandleInTransaction(org.flywaydb.core.api.callback.Event event,
                        org.flywaydb.core.api.callback.Context context) {
                    return false;
                }

                @Override
                public void handle(org.flywaydb.core.api.callback.Event event,
                        org.flywaydb.core.api.callback.Context context) {
                    try {
                        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException("the app login could not join ipaas_app", e);
                    }
                }

                @Override
                public String getCallbackName() {
                    return "t183GrantAppRoleAfterMigrate";
                }
            };
        }
    }

    // ------------------------------------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("the composition root composes both halves: routing's DbAccountWidth and config's DbAccountWidthCheck")
    void bothHalvesOfTheSeamAreWired() {
        assertThat(routingsWidth.getIfAvailable()).as("routing's DbAccountWidth bean").isNotNull();
        assertThat(configsCheck.getIfAvailable()).as("config's DbAccountWidthCheck bean (the composition root's adapter)")
                .isNotNull();
    }

    @Test
    @DisplayName("WIDE: ALL privileges on the table -> approve is 409 DB_ACCOUNT_TOO_WIDE, the row stays PENDING_APPROVAL")
    void aWideAccountIsRefusedAtApproval() throws Exception {
        Approval approval = requestAndApprove("wide", "GRANT ALL ON TABLE " + TABLE + " TO %I", true);

        assertThat(approval.answer().statusCode()).as("approve: %s", approval.answer().body()).isEqualTo(409);
        String message = JSON.readTree(approval.answer().body()).path("error").asString();
        assertThat(message).startsWith("DB_ACCOUNT_TOO_WIDE").contains(approval.account())
                .contains("DELETE").contains("UPDATE").contains("TRUNCATE").contains("TRIGGER")
                .doesNotContain("DB_ACCOUNT_UNVERIFIED");
        assertThat(rowStatus(approval.rowId())).as("the refused approval changed nothing").isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("NARROW: INSERT and SELECT only -> approve is 200 APPROVED")
    void aNarrowAccountIsApproved() throws Exception {
        Approval approval = requestAndApprove("narrow", "GRANT INSERT, SELECT ON TABLE " + TABLE + " TO %I", true);

        assertThat(approval.answer().statusCode()).as("approve: %s", approval.answer().body()).isEqualTo(200);
        assertThat(JSON.readTree(approval.answer().body()).path("status").asString()).isEqualTo("APPROVED");
        assertThat(rowStatus(approval.rowId())).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("NO CREDENTIAL in the vault (a narrow account): fail closed -> 409 DB_ACCOUNT_UNVERIFIED, still PENDING_APPROVAL")
    void anAccountThatCannotBeProbedIsRefused() throws Exception {
        Approval approval = requestAndApprove("nocred", "GRANT INSERT, SELECT ON TABLE " + TABLE + " TO %I", false);

        assertThat(approval.answer().statusCode()).as("approve: %s", approval.answer().body()).isEqualTo(409);
        assertThat(JSON.readTree(approval.answer().body()).path("error").asString())
                .startsWith("DB_ACCOUNT_UNVERIFIED").contains("NO_CREDENTIAL");
        assertThat(rowStatus(approval.rowId())).isEqualTo("PENDING_APPROVAL");
    }

    // ---------------------------------------------------------------------------------------------------- flow

    private record Approval(String account, String rowId, HttpResponse<String> answer) {
    }

    /** One tenant, one DB system on the partner database, the account, optionally its vault password; then request + approve. */
    private Approval requestAndApprove(String word, String grantTemplate, boolean storePassword) throws Exception {
        String account = "t183_" + word + "_" + Long.toUnsignedString(RANDOM.nextLong(), 36);
        byte[] secret = new byte[18];
        RANDOM.nextBytes(secret);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        createPartnerAccount(account, password, grantTemplate);

        UUID tenantId = UUID.randomUUID();
        UUID systemId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String settings = "{\"host\":\"localhost\",\"port\":" + TARGET_DB.getMappedPort(5432) + ",\"database\":\""
                + TARGET_DB.getDatabaseName() + "\",\"user\":\"" + account + "\"}";
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            execute(owner, "INSERT INTO public.tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE',"
                    + " 'Etc/UTC')", tenantId, "tnt-t183-" + word + "-" + tenantId.toString().substring(0, 8));
            execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction, type, settings)"
                    + " VALUES (?::uuid, ?::uuid, 'partner', 'Partner', 'TARGET', 'DB', ?::jsonb)",
                    systemId, tenantId, settings);
            execute(owner, "INSERT INTO public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                    + " http_method, auth_method, timeout_ms, idempotent_receive) VALUES (?::uuid, ?::uuid, ?::uuid,"
                    + " 'partner-orders', 'Partner orders', ?, 'POST', 'NO_AUTH', 5000, true)", endpointId, tenantId,
                    systemId, "jdbc:postgresql://localhost/" + TARGET_DB.getDatabaseName());
        }
        if (storePassword) {
            HttpResponse<String> saved = call("PUT", "/api/v1/admin/target-endpoints/" + endpointId + "/credentials",
                    userToken(tenantId, "TENANT_ADMIN"), "{\"fields\":{\"accountPassword\":\"" + password + "\"}}");
            assertThat(saved.statusCode()).as("store the account password: %s", saved.body()).isEqualTo(200);
        }
        String path = "/api/v1/admin/systems/" + systemId + "/approved-tables";
        HttpResponse<String> requested = call("POST", path, userToken(tenantId, "CONFIGURATOR"),
                "{\"table\":\"" + TABLE + "\"}");
        assertThat(requested.statusCode()).as("request: %s", requested.body()).isEqualTo(201);
        String rowId = JSON.readTree(requested.body()).path("id").asString();
        assertThat(rowStatus(rowId)).isEqualTo("PENDING_APPROVAL");
        return new Approval(account, rowId, call("POST", path + "/" + rowId + "/approve",
                userToken(tenantId, "APPROVER"), null));
    }

    private static synchronized void createPartnerAccount(String account, String password, String grantTemplate)
            throws Exception {
        try (Connection partner = DriverManager.getConnection(TARGET_DB.getJdbcUrl(), TARGET_DB.getUsername(),
                TARGET_DB.getPassword()); Statement create = partner.createStatement()) {
            if (!tableCreated) {
                create.execute("create table if not exists " + TABLE
                        + " (order_no text, customer text, total numeric, delivery_id uuid unique)");
                tableCreated = true;
            }
            try (PreparedStatement role = partner.prepareStatement("select format('CREATE ROLE %I LOGIN PASSWORD %L',"
                    + " ?::text, ?::text)")) {
                role.setString(1, account);
                role.setString(2, password);
                try (ResultSet ddl = role.executeQuery()) {
                    ddl.next();
                    create.execute(ddl.getString(1));
                }
            }
            try (PreparedStatement grant = partner.prepareStatement("select format(?::text, ?::text)")) {
                grant.setString(1, grantTemplate);
                grant.setString(2, account);
                try (ResultSet ddl = grant.executeQuery()) {
                    ddl.next();
                    create.execute(ddl.getString(1));
                }
            }
        }
    }

    /** A Keycloak service-account token whose subject is registered as a user of the tenant with one role. */
    private String userToken(UUID tenantId, String role) throws Exception {
        ClientCredentials client = KEYCLOAK.createConfidentialClient(
                "t183-" + role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8), true);
        String token = KEYCLOAK.clientCredentialsToken(client.clientId(), client.secret());
        var claims = JSON.readTree(new String(Base64.getUrlDecoder().decode(token.split("[.]")[1]),
                StandardCharsets.UTF_8));
        UUID userId = UUID.randomUUID();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            execute(owner, "insert into app_user (id, tenant_id, issuer, subject, status)"
                    + " values (?::uuid, ?::uuid, ?, ?, 'ACTIVE')", userId, tenantId, claims.path("iss").asString(),
                    claims.path("sub").asString());
            execute(owner, "insert into role_grant (tenant_id, user_id, role) values (?::uuid, ?::uuid, ?)", tenantId,
                    userId, role);
        }
        return token;
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return HTTP.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String rowStatus(String rowId) throws Exception {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                PreparedStatement read = owner.prepareStatement(
                        "select status from public.db_target_approved_table where id = ?::uuid")) {
            read.setString(1, rowId);
            try (ResultSet rows = read.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static void execute(Connection owner, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i] instanceof UUID id ? id.toString() : values[i]);
            }
            statement.executeUpdate();
        }
    }
}
