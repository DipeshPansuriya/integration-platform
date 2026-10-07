package io.integrationplatform.tenancy.internal.web;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * E4 (T-097), ADR-007 Decision 1: the console's one client registration ({@value ConsoleSettings#REGISTRATION_ID}),
 * built from the issuer's discovery document on first use - nothing is fetched at start-up, so the application starts
 * while the identity provider is down (the resource server's decoder is lazy the same way). Discovery goes through
 * {@link IdentityProviderClient} (R13). Only a successful discovery is kept; a failed one is tried again on the next
 * login.
 *
 * <p>Authorization Code with PKCE (S256) for the confidential client - PKCE is required on the registration, not left to
 * the client type. Scope {@code openid} only: no user-info call is made (the tenant comes from our directory).
 */
final class ConsoleClientRegistrations implements ClientRegistrationRepository {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() { };

    private final String issuer;
    private final ConsoleSettings settings;
    private final RestTemplate identityProvider;
    private final AtomicReference<ClientRegistration> discovered = new AtomicReference<>();

    ConsoleClientRegistrations(String issuer, ConsoleSettings settings, RestTemplate identityProvider) {
        this.issuer = issuer;
        this.settings = settings;
        this.identityProvider = identityProvider;
    }

    /** The R13 client every console call to the identity provider uses (discovery, token endpoint, ID-token keys). */
    RestTemplate identityProvider() {
        return identityProvider;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!ConsoleSettings.REGISTRATION_ID.equals(registrationId)) {
            return null;
        }
        ClientRegistration known = discovered.get();
        if (known != null) {
            return known;
        }
        discovered.compareAndSet(null, discover());
        return discovered.get();
    }

    private ClientRegistration discover() {
        Map<String, Object> metadata;
        try {
            metadata = identityProvider.exchange(issuer + "/.well-known/openid-configuration", HttpMethod.GET, null,
                    JSON_OBJECT).getBody();
        } catch (RestClientException unreachable) {
            throw new IdentityProviderUnavailableException(unreachable);
        }
        if (metadata == null || !issuer.equals(metadata.get("issuer"))) {
            // A discovery document of another issuer is never trusted (same rule as the resource server).
            throw new IllegalStateException("The identity provider's discovery document names another issuer");
        }
        return ClientRegistration.withRegistrationId(ConsoleSettings.REGISTRATION_ID)
                .clientId(settings.clientId())
                .clientSecret(settings.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(settings.redirectUri())
                .scope("openid")
                .authorizationUri(endpoint(metadata, "authorization_endpoint"))
                .tokenUri(endpoint(metadata, "token_endpoint"))
                .jwkSetUri(endpoint(metadata, "jwks_uri"))
                .issuerUri(issuer)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .providerConfigurationMetadata(metadata)
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .clientName("Console")
                .build();
    }

    private static String endpoint(Map<String, Object> metadata, String name) {
        Object value = metadata.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("The identity provider's discovery document has no " + name);
        }
        return text;
    }

    /** The identity provider could not be reached (after the R13 controls): a login cannot start right now. */
    static final class IdentityProviderUnavailableException extends RuntimeException {

        IdentityProviderUnavailableException(Throwable cause) {
            super("The identity provider cannot be reached", cause);
        }
    }
}
