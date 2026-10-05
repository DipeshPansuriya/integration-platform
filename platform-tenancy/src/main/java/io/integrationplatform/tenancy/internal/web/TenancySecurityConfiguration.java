package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.internal.principal.PrincipalTenantResolver;
import io.integrationplatform.tenancy.internal.principal.RequestTenantContext;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

/**
 * The OIDC resource server and the tenant-from-identity chain (T-001; ADR-007 Decisions 2 and 4, architecture.md
 * section 5 control T1).
 *
 * <ul>
 *   <li>/actuator/health is open; /api/** needs a valid bearer token (stateless, CSRF off for the bearer API only);
 *       everything else is denied.</li>
 *   <li>Tokens: issuer and audience from {@code OIDC_ISSUER_URI} / {@code OIDC_AUDIENCE} (tenancy-security.yml, no
 *       default - the application does not start without them), RS256 and ES256 only, {@code exp} required, at most
 *       60 s clock skew, keys from the issuer's JWKS. 401 and 403 are final; nothing retries them.</li>
 *   <li>Every identity-provider call (discovery, JWKS) carries the five controls of R13 / ADR-007 Decision 4:
 *       (1) a 2 s timeout; (2) retry of transient failures only, with exponential backoff, jitter and a cap;
 *       (3) one circuit breaker {@code identity-provider}; (4) a fallback - the last fetched keys keep validating
 *       while the provider is unreachable; (5) metrics and a trace span ({@link IdentityProviderClient},
 *       {@link OutageTolerantKeySetCache}; settings {@code platform.tenancy.identity-provider.*}). A provider that
 *       cannot be reached with no matching cached key means 401, never a 5xx.</li>
 *   <li>After authentication {@link PrincipalTenantResolver} binds the tenant through the login bootstrap; then
 *       {@link TenantForgeryFilter} records any tenant the request names itself.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class TenancySecurityConfiguration {

    static final String ISSUER_URI = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    static final String AUDIENCES = "spring.security.oauth2.resourceserver.jwt.audiences";
    static final String JWS_ALGORITHMS = "spring.security.oauth2.resourceserver.jwt.jws-algorithms";

    /** ADR-007 Decision 4: never HMAC, never none. */
    static final Set<SignatureAlgorithm> ALLOWED_ALGORITHMS = EnumSet.of(SignatureAlgorithm.RS256, SignatureAlgorithm.ES256);
    static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(60);
    static final Duration JWKS_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    SecurityFilterChain tenancySecurityFilterChain(HttpSecurity http, LoginBootstrap loginBootstrap,
            TenantDenialRecorder tenantDenialRecorder, RequestTenantContext requestTenantContext) throws Exception {
        AuthenticationEntryPoint entryPoint =
                new NoIdentityEntryPoint(new BearerTokenAuthenticationEntryPoint(), tenantDenialRecorder);
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // The springdoc document the console's typed client is generated against (E3 T-158/T-094,
                        // wave-0 glue): metadata, not tenant data - reachable to an authenticated caller only,
                        // never anonymously (fail-closed; the tenancy chain denies everything else).
                        .requestMatchers("/v3/api-docs/**").authenticated()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .oauth2ResourceServer(server -> server
                        .authenticationEntryPoint(entryPoint)
                        .jwt(Customizer.withDefaults()))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .addFilterAfter(new PrincipalTenantResolver(loginBootstrap, tenantDenialRecorder),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new TenantForgeryFilter(requestTenantContext, tenantDenialRecorder),
                        PrincipalTenantResolver.class);
        return http.build();
    }

    @Bean
    JwtDecoder tenancyJwtDecoder(Environment environment, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<ObservationRegistry> observations) {
        String issuer = required(environment, ISSUER_URI);
        List<String> audiences = Arrays.stream(required(environment, AUDIENCES).split(","))
                .map(String::strip).filter(a -> !a.isEmpty()).toList();
        Set<SignatureAlgorithm> algorithms = allowedAlgorithms(Binder.get(environment)
                .bind(JWS_ALGORITHMS, Bindable.listOf(String.class))
                .orElseThrow(() -> new IllegalStateException(JWS_ALGORITHMS + " is required")));
        // Without actuator (platform-tenancy tests) nothing is recorded; platform-app's registries are picked up.
        return jwtDecoder(issuer, audiences, algorithms, IdentityProviderSettings.from(environment),
                meters.getIfAvailable(CompositeMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    RequestTenantContext requestTenantContext() {
        return new RequestTenantContext();
    }

    @Bean
    TenantDenialRecorder tenantDenialRecorder() {
        return new LoggingTenantDenialRecorder();
    }

    /**
     * Lazy: nothing is fetched from the identity provider until the first token arrives; a failed discovery is tried
     * again on the next token (only a successful one is kept). Discovery and JWKS both go through
     * {@link IdentityProviderClient}; keys live in the {@link OutageTolerantKeySetCache}. When the provider cannot be
     * reached and no cached key matches, the token is refused as invalid (401) - never accepted, never a 5xx.
     */
    static JwtDecoder jwtDecoder(String issuer, List<String> audiences, Set<SignatureAlgorithm> algorithms,
            IdentityProviderSettings settings, MeterRegistry meters, ObservationRegistry observations) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(MAX_CLOCK_SKEW);
        timestamps.setAllowEmptyExpiryClaim(false);
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && !Collections.disjoint(aud, audiences)));
        RestTemplate rest = IdentityProviderClient.restTemplate(settings, meters, observations);
        OutageTolerantKeySetCache keys =
                new OutageTolerantKeySetCache(settings.keySetTtl(), settings.keySetFallbackMaxAge());
        JwtDecoder decoder = new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder nimbus = NimbusJwtDecoder.withIssuerLocation(issuer)
                    .restOperations(rest)
                    .cache(keys)
                    .jwsAlgorithms(allowed -> {
                        allowed.clear();
                        allowed.addAll(algorithms);
                    })
                    .build();
            nimbus.setJwtValidator(validator);
            return nimbus;
        });
        return token -> {
            try {
                return decoder.decode(token);
            } catch (BadJwtException invalid) {
                throw invalid;
            } catch (JwtException | JwtDecoderInitializationException providerUnavailable) {
                throw new BadJwtException("The token cannot be verified: the identity provider is unavailable",
                        providerUnavailable);
            }
        };
    }

    private static String required(Environment environment, String key) {
        // Resolves ${OIDC_...} strictly: an unset variable fails the start-up and names the variable.
        String value = environment.getRequiredProperty(key);
        if (value.isBlank()) {
            throw new IllegalStateException(key + " is blank");
        }
        return value.strip();
    }

    private static Set<SignatureAlgorithm> allowedAlgorithms(List<String> names) {
        Set<SignatureAlgorithm> algorithms = EnumSet.noneOf(SignatureAlgorithm.class);
        for (String name : names) {
            SignatureAlgorithm algorithm = SignatureAlgorithm.from(name.strip());
            if (algorithm == null || !ALLOWED_ALGORITHMS.contains(algorithm)) {
                throw new IllegalStateException(
                        JWS_ALGORITHMS + " allows only RS256 and ES256 (ADR-007 Decision 4), not " + name.strip());
            }
            algorithms.add(algorithm);
        }
        if (algorithms.isEmpty()) {
            throw new IllegalStateException(JWS_ALGORITHMS + " is empty");
        }
        return algorithms;
    }
}
