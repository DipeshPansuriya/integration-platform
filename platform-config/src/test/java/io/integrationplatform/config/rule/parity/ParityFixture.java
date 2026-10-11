package io.integrationplatform.config.rule.parity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.integrationplatform.tenancy.internal.principal.TenantBoundAuthentication;
import io.integrationplatform.tenancy.port.LoginBinding;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-029's container fixture: one PostgreSQL per JVM with the platform's checked-in migrations (tenancy + config, as
 * the owner), a restricted LOGIN granted {@code ipaas_app} that the composed app connects as (random password, memory
 * only - R07), one seeded tenant with an ACTIVE TENANT_ADMIN user (role_grant) and one flow, and bearers signed with a
 * key generated per run. The E6 lane B fixture (RuleSuiteFixture) is package-private to {@code config.rule}, so this
 * lane keeps its own small copy of the same pattern instead of editing another lane's file.
 */
final class ParityFixture {

    /** The platform's pinned PostgreSQL tag (R05; identical to TestImages.POSTGRES and RuleSuiteFixture). */
    static final String POSTGRES_TAG = "postgres:17.11-alpine";

    static final URI OIDC_ISSUER = URI.create("https://issuer.e6-parity.invalid/realms/platform");
    static final String OIDC_AUDIENCE = "integration-platform-api";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final RSAKey KEY = generateKey();

    private static PostgreSQLContainer postgres;
    private static Login login;
    private static Tenant tenant;

    private record Login(String username, String password) {

        @Override
        public String toString() {
            return "Login[" + username + "]";
        }
    }

    /** The seeded tenant: its TENANT_ADMIN user (bound by issuer + subject) and one flow of its own. */
    record Tenant(UUID id, String code, UUID userId, String subject, UUID flowId) {
    }

    record Response(int status, String body) {

        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    private ParityFixture() {
    }

    // ------------------------------------------------------------------ wiring

    /** Starts and seeds once per JVM, then registers the fixed environment the composed app reads (R07). */
    static synchronized void wire(DynamicPropertyRegistry registry) {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(POSTGRES_TAG);
            postgres.start();
            deployMigrations();
            login = createRestrictedLogin();
            tenant = seedTenant();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", login::username);
        registry.add("spring.datasource.password", login::password);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", OIDC_ISSUER::toString);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> OIDC_AUDIENCE);
        registry.add("spring.security.oauth2.resourceserver.jwt.jws-algorithms", () -> "RS256");
    }

    static Tenant tenant() {
        return tenant;
    }

    /** The decoder the composed app verifies the suite's bearers with (the issuer is never contacted). */
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
            throw new IllegalStateException("could not build the suite decoder", e);
        }
    }

    /** A 5-minute bearer for the tenant's seeded user. */
    static String bearer(Tenant caller) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(OIDC_ISSUER.toString())
                .subject(caller.subject())
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
            throw new IllegalStateException("could not sign the suite bearer", e);
        }
    }

    // ------------------------------------------------------------------ the two paths

    /** The panel's path: a real HTTP call to the booted app as the seeded TENANT_ADMIN. */
    static Response call(int port, String method, String path, Tenant caller, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + bearer(caller));
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException("the real HTTP call failed: " + method + " " + path, e);
        }
    }

    /**
     * The runtime's path: runs {@code work} on this thread with the tenant bound exactly as the identity chain binds
     * it for a request (the verified bearer + the directory binding), so the port's transaction is stamped with the
     * tenant by the real stamping transaction manager and RLS applies. The binding is cleared afterwards.
     */
    static <T> T asTenant(Tenant bound, Supplier<T> work) {
        JwtAuthenticationToken verified = new JwtAuthenticationToken(decoder().decode(bearer(bound)));
        LoginBinding binding = new LoginBinding(bound.userId(), "ACTIVE", bound.id(), bound.code(), "ACTIVE",
                ZoneId.of("Etc/UTC"));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TenantBoundAuthentication(verified, binding));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------ seeding

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void deployMigrations() {
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
    }

    private static Login createRestrictedLogin() {
        String username = "parity_login_" + Long.toUnsignedString(RANDOM.nextLong(), 36);
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        Login created = new Login(username, Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        executeFormatted("CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD %L",
                created.username(), created.password());
        executeFormatted("GRANT %I TO %I", "ipaas_app", created.username());
        return created;
    }

    /** DDL takes no bind parameters, so it is built server-side by format() with %I / %L quoting. */
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
            throw new IllegalStateException("the formatted DDL failed: " + template, e);
        }
    }

    private static Tenant seedTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID flowId = UUID.randomUUID();
        String suffix = Long.toUnsignedString(RANDOM.nextLong(), 36);
        String code = "tnt-parity-" + suffix;
        String subject = "sub-parity-" + UUID.randomUUID();
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')",
                    tenantId, code);
            execute(owner, "INSERT INTO app_user (id, tenant_id, issuer, subject, status)"
                    + " VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')", userId, tenantId, OIDC_ISSUER, subject);
            execute(owner, "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, 'TENANT_ADMIN')",
                    tenantId, userId);
            execute(owner, "INSERT INTO public.interface (id, tenant_id, code, name) VALUES (?::uuid, ?::uuid, ?, ?)",
                    flowId, tenantId, "flow-parity-" + suffix, "parity flow");
        } catch (SQLException e) {
            throw new IllegalStateException("the tenant seed failed", e);
        }
        return new Tenant(tenantId, code, userId, subject, flowId);
    }

    private static void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, String.valueOf(values[i]));
            }
            statement.executeUpdate();
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate the suite signing key", e);
        }
    }
}
