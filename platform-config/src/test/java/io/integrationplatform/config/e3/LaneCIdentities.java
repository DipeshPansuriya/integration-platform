package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Lane C's test identities (the TestTokens shape, one copy because the poms are the seam): bearers signed with a key
 * pair generated per run (no key material in any file, R07), the matching decoder (RS256, issuer, audiences, exp),
 * and the fixtures the approval scenarios need that E3ConfigDatabase does not seed - a SECOND app user of tenant A
 * (the approver who is not the author must be a user of the same tenant, ADR-007 Decision 2), which lane C seeds
 * beside lane A's own seed through the owner connection.
 */
public final class LaneCIdentities {

    private final RSAKey key;

    private static final SecureRandom RANDOM = new SecureRandom();

    public LaneCIdentities() {
        try {
            key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate the test signing key", e);
        }
    }

    /** A 5-minute access token for (test issuer, subject) with the platform audience. */
    String accessToken(String subject) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(E3ConfigDatabase.OIDC_ISSUER.toString())
                .subject(subject)
                .audience(E3ConfigDatabase.OIDC_AUDIENCE)
                .issueTime(java.util.Date.from(now))
                .notBeforeTime(java.util.Date.from(now.minusSeconds(5)))
                .expirationTime(java.util.Date.from(now.plus(Duration.ofMinutes(5))))
                .jwtID(UUID.randomUUID().toString())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the test token", e);
        }
    }

    /**
     * The decoder the suite's composed system verifies its tokens with: the real ADR-007 validation (issuer,
     * audiences, expiry) against the suite's in-memory public key, so the issuer is never contacted.
     */
    public JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(E3ConfigDatabase.OIDC_ISSUER.toString()),
                    new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                            aud -> aud != null && aud.contains(E3ConfigDatabase.OIDC_AUDIENCE))));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException("could not build the test decoder", e);
        }
    }

    /** A code the suites generate per call (per-tenant uniqueness; never a file, R07). */
    static String randomCode(String prefix) {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(prefix).append('-');
        for (byte b : bytes) {
            out.append(Character.forDigit((b & 0x0f) % 36, 36));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ the tenant-A approver (not the author)

    /**
     * Seeds a second ACTIVE app_user of tenant A and returns the login that user's bearer binds. "Approved by
     * another user" needs an approver inside the author's tenant - E3ConfigDatabase seeds only one user per tenant,
     * and lane C may not edit lane A's fixture, so the approver is seeded beside that seed through the owner
     * connection. Runs after {@code E3ConfigDatabase.seedDirectory()} (the tenants must exist first).
     */
    static String seedApproverOfTenantA() {
        E3ConfigDatabase.SeededTenant tenantA = E3ConfigDatabase.tenantA();
        String subject = "sub-approver-" + UUID.randomUUID();
        try (Connection owner = E3ConfigDatabase.ownerConnection()) {
            try (PreparedStatement user = owner.prepareStatement(
                    "INSERT INTO app_user (id, tenant_id, issuer, subject, status) "
                            + "VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')")) {
                user.setString(1, UUID.randomUUID().toString());
                user.setString(2, tenantA.id().toString());
                user.setString(3, E3ConfigDatabase.OIDC_ISSUER.toString());
                user.setString(4, subject);
                user.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed the tenant-A approver", e);
        }
        return subject;
    }

    // ------------------------------------------------------------------ owner-side reads the scenarios assert with

    /** The refusal rows of one tenant's one version (the T-019 refusal record; the owner path only reads here). */
    static List<Map<String, String>> refusals(UUID tenantId, UUID versionId) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select refusal, actor_id::text from public.config_version_refusal "
                                + "where tenant_id = ?::uuid and version_id = ?::uuid order by recorded_at")) {
            read.setString(1, tenantId.toString());
            read.setString(2, versionId.toString());
            try (ResultSet rs = read.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("refusal", rs.getString(1));
                    row.put("actor", rs.getString(2));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ sanity assertion the suites run first

    /** The login exists, the tenants are seeded and the approver binds: the premise before any scenario. */
    static void assertPremise(E3ConfigDatabase.RestrictedLogin login, String approverSubject) {
        assertThat(login.username()).as("the restricted login was created").isNotBlank();
        assertThat(E3ConfigDatabase.tenantA()).as("tenant A is seeded").isNotNull();
        assertThat(E3ConfigDatabase.tenantB()).as("tenant B is seeded").isNotNull();
        assertThat(approverSubject).as("the tenant-A approver is seeded").isNotBlank();
        // The premise the security chain rests on: the author's bearer subject resolves through the same
        // definer function the PrincipalTenantResolver calls, over the same restricted login the app uses.
        try (java.sql.Connection app = java.sql.DriverManager.getConnection(
                E3ConfigDatabase.postgres().getJdbcUrl(), login.username(), login.password());
                java.sql.PreparedStatement call = app.prepareStatement(
                        "select user_id, tenant_id from public.ipaas_login_bootstrap(?, ?)")) {
            call.setString(1, E3ConfigDatabase.OIDC_ISSUER.toString());
            call.setString(2, E3ConfigDatabase.tenantA().subject());
            try (java.sql.ResultSet rs = call.executeQuery()) {
                assertThat(rs.next())
                        .as("the author's bearer resolves through the login bootstrap over the restricted login")
                        .isTrue();
            }
        } catch (SQLException bootstrapUnreachable) {
            throw new AssertionError("the login bootstrap call failed over the restricted login: "
                    + bootstrapUnreachable.getMessage(), bootstrapUnreachable);
        }
    }
}