package io.integrationplatform.contract;

import io.integrationplatform.PlatformTestWiring;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Bearer tokens for the T-094 typed-client contract suite - the same shape as the E1 denial suite's
 * {@code TestTokens} (package-private there; platform-app has the nimbus classes on its test classpath through
 * platform-tenancy), copied here because the original belongs to another lane's test package and must not be
 * edited. Signed with a key pair generated per run (no key material in any file, R07); the issuer is never
 * contacted because the suite's decoder validates against the in-memory public key.
 */
final class ContractTestTokens {

    private final RSAKey key;

    ContractTestTokens() {
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
                .issuer(PlatformTestWiring.OIDC_ISSUER.toString())
                .subject(subject)
                .audience(PlatformTestWiring.OIDC_AUDIENCE)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .jwtID(UUID.randomUUID().toString())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the test token", e);
        }
    }

    JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(PlatformTestWiring.OIDC_ISSUER.toString()),
                    new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                            aud -> aud != null && aud.contains(PlatformTestWiring.OIDC_AUDIENCE))));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException("could not build the test decoder", e);
        }
    }
}