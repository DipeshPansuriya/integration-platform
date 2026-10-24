package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.web.TenantFixtures.ALICE;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.AUDIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * T209 item 1: the issuer check of the decoder itself. Through the HTTP chain a foreign issuer is also refused by the
 * (issuer, azp) binding in the principal resolver, so removing {@code JwtIssuerValidator} left those tests green. This
 * test calls the decoder directly: a token that is validly signed by a published key, with a good audience and
 * expiry, but with a foreign {@code iss}, must fail here.
 */
class JwtDecoderIssuerTest {

    private LocalIssuer issuer;

    @BeforeEach
    void start() {
        issuer = LocalIssuer.start();
    }

    @AfterEach
    void stop() {
        issuer.close();
    }

    private JwtDecoder decoder() {
        IdentityProviderSettings settings = new IdentityProviderSettings(3, Duration.ofMillis(60), 2.0, 0.5,
                Duration.ofMillis(300), 5, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofHours(1));
        return TenancySecurityConfiguration.jwtDecoder(issuer.issuer(), List.of(AUDIENCE),
                Set.of(SignatureAlgorithm.RS256, SignatureAlgorithm.ES256), settings, new SimpleMeterRegistry(),
                TestObservationRegistry.create());
    }

    @Test
    void aValidlySignedTokenOfTheConfiguredIssuerIsDecoded() {
        assertThat(decoder().decode(issuer.rs256(issuer.claims(ALICE, c -> { }))).getSubject()).isEqualTo(ALICE);
    }

    @Test
    void aValidlySignedTokenWithAForeignIssuerIsRejectedByTheDecoder() {
        String token = issuer.rs256(issuer.claims(ALICE, c -> c.issuer("https://other-issuer.test/realms/platform")));

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(BadJwtException.class);
    }
}
