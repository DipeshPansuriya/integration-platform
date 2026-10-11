package io.integrationplatform.config.e5.system;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E5 lane B's container fixture (T-102, T-104): one PostgreSQL per JVM with the checked-in migrations of the tenant
 * directory and the config module applied as the owner, a restricted login granted {@code ipaas_app} (the app connects
 * as it only), tenants A and B, and bearers signed with a key generated per run (no key material in any file, R07).
 * The E3 fixtures in {@code config.e3} are package-private to that lane, so this is the same pattern in lane B's own
 * package (no file of another lane is touched).
 */
final class E5SystemFixture {

    static final String POSTGRES_TAG = "postgres:17.11-alpine"; // the platform's pinned tag (TestImages.POSTGRES)
    static final URI OIDC_ISSUER = URI.create("https://issuer.e5-systems.invalid/realms/platform");
    static final String OIDC_AUDIENCE = "integration-platform-api";
    static final String SYSTEMS = "/api/v1/admin/systems";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final RSAKey KEY = generateKey();

    private static PostgreSQLContainer postgres;
    private static String loginUser;
    private static String loginPassword;
    private static Tenant tenantA;
    private static Tenant tenantB;

    record Tenant(UUID id, String code, String adminSubject) {
    }

    /** One real HTTP answer. */
    record Response(int status, String body) {

        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    private E5SystemFixture() {
    }

    // ------------------------------------------------------------------ database

    /** Starts PostgreSQL, deploys the migrations, creates the login and seeds A and B - once per JVM. */
    static synchronized void start() {
        if (postgres != null) {
            return;
        }
        postgres = new PostgreSQLContainer(POSTGRES_TAG);
        postgres.start();
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        List<String> locations = new ArrayList<>();
        for (Path folder : List.of(basedir.getParent().resolve("platform-tenancy/src/main/resources/db/migration"),
                basedir.resolve("src/main/resources/db/migration"))) {
            if (Files.isDirectory(folder)) {
                locations.add("filesystem:" + folder.toString().replace('\\', '/'));
            }
        }
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        loginUser = "app_login_" + Long.toUnsignedString(RANDOM.nextLong(), 36);
        loginPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        executeFormatted("CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD %L",
                loginUser, loginPassword);
        executeFormatted("GRANT %I TO %I", "ipaas_app", loginUser);
        tenantA = seedTenant("alpha");
        tenantB = seedTenant("bravo");
    }

    /** The app's datasource (the restricted login), Flyway (the owner) and the resource server's fixed settings. */
    static void wire(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> loginUser);
        registry.add("spring.datasource.password", () -> loginPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", OIDC_ISSUER::toString);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> OIDC_AUDIENCE);
        registry.add("spring.security.oauth2.resourceserver.jwt.jws-algorithms", () -> "RS256");
    }

    static Tenant tenantA() {
        return tenantA;
    }

    static Tenant tenantB() {
        return tenantB;
    }

    static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Tenant seedTenant(String word) {
        UUID tenantId = UUID.randomUUID();
        String code = "tnt-e5sys-" + word + "-" + Long.toUnsignedString(RANDOM.nextLong(), 36);
        try (Connection owner = ownerConnection();
                PreparedStatement tenant = owner.prepareStatement(
                        "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            tenant.setString(1, tenantId.toString());
            tenant.setString(2, code);
            tenant.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed tenant " + word, e);
        }
        return new Tenant(tenantId, code, newUser(tenantId, "TENANT_ADMIN"));
    }

    /**
     * A fresh ACTIVE user of the tenant holding {@code role} (a role_grant row - every role-gated /api needs one); its
     * subject is what {@link #bearer} signs. Fresh users keep the per-user test-connection rate limit of one scenario
     * out of the next.
     */
    static String newUser(UUID tenantId, String role) {
        UUID userId = UUID.randomUUID();
        String subject = "sub-e5sys-" + UUID.randomUUID();
        try (Connection owner = ownerConnection()) {
            try (PreparedStatement user = owner.prepareStatement("INSERT INTO app_user (id, tenant_id, issuer, subject,"
                    + " status) VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')")) {
                user.setString(1, userId.toString());
                user.setString(2, tenantId.toString());
                user.setString(3, OIDC_ISSUER.toString());
                user.setString(4, subject);
                user.executeUpdate();
            }
            try (PreparedStatement grant = owner.prepareStatement(
                    "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, ?)")) {
                grant.setString(1, tenantId.toString());
                grant.setString(2, userId.toString());
                grant.setString(3, role);
                grant.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed a user", e);
        }
        return subject;
    }

    /** One text value (owner connection, RLS bypassed): the evidence of what the database holds. */
    static String scalar(String sql, String... values) {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the evidence query failed: " + sql, e);
        }
    }

    static void execute(String sql, String... values) {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("the fixture statement failed: " + sql, e);
        }
    }

    /** DDL takes no bind parameters: built server-side with format() and %I / %L quoting. */
    private static void executeFormatted(String template, String... args) {
        StringBuilder call = new StringBuilder("SELECT format(?");
        for (int i = 0; i < args.length; i++) {
            call.append(", ?");
        }
        call.append(')');
        try (Connection owner = ownerConnection()) {
            String ddl;
            try (PreparedStatement format = owner.prepareStatement(call.toString())) {
                format.setString(1, template);
                for (int i = 0; i < args.length; i++) {
                    format.setString(i + 2, args[i]);
                }
                try (ResultSet rs = format.executeQuery()) {
                    rs.next();
                    ddl = rs.getString(1);
                }
            }
            try (Statement statement = owner.createStatement()) {
                statement.execute(ddl);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the fixture DDL failed", e);
        }
    }

    static String randomCode(String prefix) {
        return prefix + "-" + Long.toUnsignedString(RANDOM.nextLong(), 36);
    }

    // ------------------------------------------------------------------ identities

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate the test signing key", e);
        }
    }

    /** A 5-minute access token for (test issuer, subject) with the platform audience. */
    static String bearer(String subject) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(OIDC_ISSUER.toString())
                .subject(subject)
                .audience(OIDC_AUDIENCE)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .jwtID(UUID.randomUUID().toString())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the test token", e);
        }
    }

    /** The decoder the suite's app verifies with: the real ADR-007 checks against the in-memory public key. */
    static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(OIDC_ISSUER.toString()),
                    new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                            aud -> aud != null && aud.contains(OIDC_AUDIENCE))));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException("could not build the test decoder", e);
        }
    }

    // ------------------------------------------------------------------ real HTTP

    static Response call(int port, String method, String path, String subject, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (subject != null) {
            request.header("Authorization", "Bearer " + bearer(subject));
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException("the real HTTP call failed: " + method + " " + path, e);
        }
    }

    /** {@code {"code":..,"name":..,"direction":..,"type":..,"settings":<json>}} - type/settings left out when null. */
    static String systemBody(String code, String direction, String type, String settingsJson) {
        StringBuilder body = new StringBuilder("{\"code\":\"").append(code).append("\",\"name\":\"system ")
                .append(code).append("\",\"direction\":\"").append(direction).append('"');
        if (type != null) {
            body.append(",\"type\":\"").append(type).append('"');
        }
        if (settingsJson != null) {
            body.append(",\"settings\":").append(settingsJson);
        }
        return body.append('}').toString();
    }
}
